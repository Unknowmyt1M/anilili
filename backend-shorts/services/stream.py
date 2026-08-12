import re
import time
import asyncio
import urllib.parse
import datetime
from typing import Optional, Dict, Any
from api.logs import add_custom_log

YOUTUBE_ID_REGEX = r"^[a-zA-Z0-9_-]{11}$"

SINGLE_PASS_FORMAT = (
    "best[protocol=m3u8_native]/"
    "bestvideo[ext=mp4][height<=1080]+bestaudio[ext=m4a]/"
    "bestvideo[ext=mp4]+bestaudio/"
    "best[ext=mp4]/"
    "best"
)

# In-flight task deduplication map: youtube_video_id -> asyncio.Task
IN_FLIGHT_LOCK = asyncio.Lock()
IN_FLIGHT_TASKS: Dict[str, asyncio.Task] = {}


def validate_youtube_id(video_id: str) -> bool:
    """Validates that YouTube video ID is exactly 11 characters conforming to standard regex."""
    if not video_id:
        return False
    return bool(re.match(YOUTUBE_ID_REGEX, video_id))


def parse_stream_expiry(url: str) -> Optional[datetime.datetime]:
    """Parses the expire query parameter (Unix timestamp) from YouTube stream URL."""
    try:
        parsed = urllib.parse.urlparse(url)
        params = urllib.parse.parse_qs(parsed.query)
        expire_ts = params.get("expire", [None])[0]
        if expire_ts:
            return datetime.datetime.fromtimestamp(
                int(expire_ts), tz=datetime.timezone.utc
            )
    except Exception:
        pass
    return None


def get_stream_expires_at(url: str) -> str:
    """
    Returns ISO 8601 string for stream expiry:
    - Deducts 5-minute safety buffer if expire query param is present.
    - Fallback to conservative 4-hour TTL if expire query param is missing.
    """
    actual = parse_stream_expiry(url)
    now = datetime.datetime.now(datetime.timezone.utc)
    if actual:
        buffered = actual - datetime.timedelta(minutes=5)
        return buffered.isoformat()
    fallback = now + datetime.timedelta(hours=4)
    return fallback.isoformat()


def is_stream_expired(expires_at_iso: Optional[str]) -> bool:
    """Checks if stream ISO timestamp is expired or within safety buffer."""
    if not expires_at_iso:
        return True
    try:
        exp = datetime.datetime.fromisoformat(expires_at_iso)
        now = datetime.datetime.now(datetime.timezone.utc)
        return exp <= now
    except Exception:
        return True


def _run_single_pass_ytdlp(video_id: str) -> Dict[str, Any]:
    """Synchronous yt-dlp execution in single pass."""
    yt_url = f"https://www.youtube.com/watch?v={video_id}"
    import yt_dlp

    opts = {
        "format": SINGLE_PASS_FORMAT,
        "extractor_args": {"youtube": {"player_client": ["android", "ios"]}},
        "quiet": True,
        "no_warnings": True,
        "no_playlist": True,
        "skip_download": True,
        "cachedir": False,
    }

    start_time = time.time()
    add_custom_log("INFO", "yt-dlp", f"Starting optimized yt-dlp extraction for video: {video_id}")

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(yt_url, download=False)
            stream_url = info.get("url")
            protocol = info.get("protocol") or ""
            ext = info.get("ext") or ""

            # Filter out non-playable formats (like mhtml or storyboards)
            if stream_url and (ext == "mhtml" or "storyboard" in stream_url):
                stream_url = None

            # If top-level url is missing or non-playable, pick best combined/playable format from formats array
            if not stream_url:
                formats = info.get("formats", [])
                valid_fmts = [
                    f for f in formats 
                    if f.get("url") and f.get("ext") != "mhtml" and "storyboard" not in f.get("url", "")
                ]
                combined = [f for f in valid_fmts if f.get("vcodec") != "none" and f.get("acodec") != "none"]
                if combined:
                    target_fmt = combined[-1]
                    stream_url = target_fmt.get("url")
                    protocol = target_fmt.get("protocol") or ""
                    ext = target_fmt.get("ext") or ""
                elif valid_fmts:
                    target_fmt = valid_fmts[-1]
                    stream_url = target_fmt.get("url")
                    protocol = target_fmt.get("protocol") or ""
                    ext = target_fmt.get("ext") or ""

            if stream_url:
                elapsed_ms = int((time.time() - start_time) * 1000)
                expires_at = get_stream_expires_at(stream_url)
                proto_str = (protocol or "").lower()
                url_str = (stream_url or "").lower()
                stream_type = "HLS" if ("m3u8" in proto_str or "m3u8" in url_str) else "MP4"

                add_custom_log(
                    "INFO",
                    "yt-dlp",
                    f"SUCCESS video: {video_id} -> type: {stream_type}, took {elapsed_ms}ms, expires: {expires_at}"
                )
                return {
                    "video_url": stream_url,
                    "stream_type": stream_type,
                    "expires_at": expires_at,
                }
    except Exception as e:
        elapsed_ms = int((time.time() - start_time) * 1000)
        add_custom_log("ERROR", "yt-dlp", f"FAILED video: {video_id} after {elapsed_ms}ms: {str(e)}")
        raise RuntimeError(f"yt-dlp extraction failed for video {video_id}: {str(e)}")

    raise RuntimeError(f"Could not extract stream URL for video {video_id}")


from services.redis_client import redis_client

async def extract_stream_url_async(video_id: str) -> Dict[str, Any]:
    """
    Deduplicated stream resolution with Upstash Redis cache & locking.
    1. Checks Upstash Redis stream cache for fast path (< 20ms).
    2. Uses Redis lock & in-flight Task map to prevent duplicate yt-dlp processes.
    """
    if not validate_youtube_id(video_id):
        raise ValueError(f"Invalid YouTube video ID format: {video_id}")

    # Step 1: Upstash Redis Fast Path
    cached_redis = await redis_client.get_stream(video_id)
    if cached_redis and not is_stream_expired(cached_redis.get("expires_at")):
        add_custom_log("INFO", "stream", f"UPSTASH REDIS FAST PATH HIT for video {video_id} < 20ms")
        return cached_redis

    # Step 2: In-Flight Lock & Deduplication
    async with IN_FLIGHT_LOCK:
        if video_id in IN_FLIGHT_TASKS:
            add_custom_log("INFO", "stream", f"DEDUPLICATED: Attaching request for {video_id} to existing task")
            task = IN_FLIGHT_TASKS[video_id]
        else:
            await redis_client.set_resolution_status(video_id, "RESOLVING", ttl_seconds=120)
            await redis_client.acquire_resolution_lock(video_id, lock_ttl_seconds=30)
            task = asyncio.create_task(asyncio.to_thread(_run_single_pass_ytdlp, video_id))
            IN_FLIGHT_TASKS[video_id] = task

    try:
        result = await task
        # Cache successful extraction into Redis
        ttl = 14400  # Default 4 hours
        exp_iso = result.get("expires_at")
        if exp_iso:
            try:
                exp_dt = datetime.datetime.fromisoformat(exp_iso)
                now_dt = datetime.datetime.now(datetime.timezone.utc)
                diff = int((exp_dt - now_dt).total_seconds())
                if diff > 60:
                    ttl = diff
            except Exception:
                pass

        await redis_client.set_stream(video_id, result, ttl_seconds=ttl)
        await redis_client.set_resolution_status(video_id, "READY", ttl_seconds=ttl)
        return result
    except Exception as e:
        await redis_client.set_resolution_status(video_id, "FAILED", ttl_seconds=120)
        raise e
    finally:
        await redis_client.release_resolution_lock(video_id)
        async with IN_FLIGHT_LOCK:
            if video_id in IN_FLIGHT_TASKS and IN_FLIGHT_TASKS[video_id] == task:
                del IN_FLIGHT_TASKS[video_id]


def extract_stream_url(video_id: str) -> Dict[str, Any]:
    """Sync wrapper for legacy calls."""
    return _run_single_pass_ytdlp(video_id)

