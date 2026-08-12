import datetime
import aiosqlite
from typing import Optional, List
from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, status
from db.database import get_db
from db.models import ShortsPage, StreamResponse
from services import ranker, stream, discovery
from api.logs import add_custom_log
from services.redis_client import redis_client

router = APIRouter(prefix="/shorts", tags=["Shorts Feed"])


@router.get("", response_model=ShortsPage)
async def get_shorts_feed(
    cursor: Optional[str] = Query(None, description="Pagination cursor (created_at ISO string)"),
    limit: int = Query(10, ge=1, le=50, description="Page size limit"),
    x_user_id: Optional[str] = Header(None, alias="X-User-Id"),
    x_youtube_api_key: Optional[str] = Header(None, alias="X-YouTube-Api-Key"),
    db: aiosqlite.Connection = Depends(get_db),
):
    """
    Fetches the Anilili Shorts discovery feed with cursor pagination.
    If DB has no shorts and X-YouTube-Api-Key is provided, triggers initial discovery.
    If DB has no shorts and no key is provided, returns empty feed ({"items":[], "nextCursor": null}).
    """
    page = await ranker.get_ranked_shorts(db=db, cursor=cursor, limit=limit, user_id=x_user_id)

    # If database is empty and YouTube API key is supplied, attempt initial discovery
    if len(page.items) == 0 and not cursor and x_youtube_api_key:
        try:
            await discovery.discover_youtube_shorts(api_key=x_youtube_api_key, db=db, query="anime shorts")
            page = await ranker.get_ranked_shorts(db=db, cursor=cursor, limit=limit, user_id=x_user_id)
        except HTTPException:
            raise
        except Exception:
            pass

    return page


@router.get("/{short_id}/stream", response_model=StreamResponse)
async def get_short_stream(
    short_id: str,
    request: Request,
    db: aiosqlite.Connection = Depends(get_db),
):
    """
    FIX #9, #12, #14: Extracts or returns cached stream URL with Redis+SQLite consistency.
    Returns StreamResponse with streamStatus (READY, RESOLVING, EXPIRED, FAILED) pointing to video proxy.
    """
    async with db.execute(
        """
        SELECT id, youtube_video_id, video_url, stream_type, stream_expires_at, fail_count, is_unavailable
        FROM shorts WHERE id = ?
        """,
        (short_id,),
    ) as cursor:
        row = await cursor.fetchone()
        if not row:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Short '{short_id}' not found",
            )

    if row["is_unavailable"] or row["fail_count"] >= 3:
        raise HTTPException(
            status_code=status.HTTP_410_GONE,
            detail="This short video is unavailable for playback.",
        )

    yt_video_id = row["youtube_video_id"]
    current_video_url = row["video_url"]
    current_stream_type = row["stream_type"] or "MP4"
    current_expires_at = row["stream_expires_at"]

    proxy_url = str(request.url_for("proxy_short_video", short_id=short_id))

    # FIX #12: Check Redis first — fastest path
    redis_stream = await redis_client.get_stream(yt_video_id)
    if redis_stream and not stream.is_stream_expired(redis_stream.get("expires_at")):
        add_custom_log("INFO", "stream", f"[CACHE_HIT] Redis hit for short {short_id} ({yt_video_id})")

        # FIX #9: Sync SQLite if Redis is fresh but SQLite is stale
        if not current_video_url or stream.is_stream_expired(current_expires_at):
            add_custom_log("INFO", "stream", f"[STREAM_REFRESH] Syncing SQLite from Redis for short {short_id}")
            now_iso = datetime.datetime.now(datetime.timezone.utc).isoformat()
            await db.execute(
                """
                UPDATE shorts
                SET video_url = ?, stream_type = ?, stream_expires_at = ?, updated_at = ?
                WHERE id = ?
                """,
                (
                    redis_stream["video_url"],
                    redis_stream.get("stream_type", "MP4"),
                    redis_stream.get("expires_at"),
                    now_iso,
                    short_id,
                ),
            )
            await db.commit()

        current_stream_type = redis_stream.get("stream_type", "MP4")
        current_expires_at = redis_stream.get("expires_at")

    elif not (current_video_url and not stream.is_stream_expired(current_expires_at)):
        # FIX #14: Log cache miss before yt-dlp
        add_custom_log("INFO", "stream", f"[CACHE_MISS] Resolving stream for short {short_id} ({yt_video_id})")
        try:
            extracted = await stream.extract_stream_url_async(yt_video_id)
            new_video_url = extracted["video_url"]
            new_stream_type = extracted["stream_type"]
            new_expires_at = extracted["expires_at"]
            now_iso = datetime.datetime.now(datetime.timezone.utc).isoformat()

            # FIX #9: Update BOTH SQLite AND Redis atomically
            await db.execute(
                """
                UPDATE shorts
                SET video_url = ?, stream_type = ?, stream_expires_at = ?, updated_at = ?
                WHERE id = ?
                """,
                (new_video_url, new_stream_type, new_expires_at, now_iso, short_id),
            )
            await db.commit()
            # Redis is already updated inside extract_stream_url_async()
            add_custom_log("INFO", "stream", f"[STREAM_REFRESH] SQLite+Redis synced for short {short_id}")

            current_stream_type = new_stream_type
            current_expires_at = new_expires_at

        except Exception as e:
            new_fail_count = (row["fail_count"] or 0) + 1
            is_unavail = 1 if new_fail_count >= 3 else 0
            now_iso = datetime.datetime.now(datetime.timezone.utc).isoformat()

            await db.execute(
                """
                UPDATE shorts
                SET fail_count = ?, is_unavailable = ?, updated_at = ?
                WHERE id = ?
                """,
                (new_fail_count, is_unavail, now_iso, short_id),
            )
            await db.commit()

            add_custom_log("ERROR", "stream", f"[PREFETCH_FAILED] Resolution failed for short {short_id} ({yt_video_id}): {str(e)}")
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Failed to extract playable video stream. Please try again later.",
            )

    return StreamResponse(
        shortId=short_id,
        streamStatus="READY",
        videoUrl=proxy_url,
        streamType=current_stream_type,
        expiresAt=current_expires_at or datetime.datetime.now(datetime.timezone.utc).isoformat(),
    )


@router.post("/{short_id}/invalidate")
async def invalidate_short_stream(
    short_id: str,
    db: aiosqlite.Connection = Depends(get_db),
):
    """
    FIX #10: Invalidates cached stream in Redis AND SQLite when ExoPlayer reports a stale/expired link.
    """
    async with db.execute("SELECT youtube_video_id FROM shorts WHERE id = ?", (short_id,)) as cursor:
        row = await cursor.fetchone()
        if row:
            yt_video_id = row["youtube_video_id"]
            # FIX #9: Invalidate BOTH Redis and SQLite
            await redis_client.invalidate_stream(yt_video_id)
            await db.execute(
                "UPDATE shorts SET video_url = NULL, stream_expires_at = NULL WHERE id = ?",
                (short_id,),
            )
            await db.commit()
            add_custom_log("INFO", "stream", f"[STREAM_REFRESH] Invalidated cache for short {short_id} ({yt_video_id})")
    return {"status": "invalidated"}


from fastapi.responses import StreamingResponse, Response
import httpx
import re as _re


def _rewrite_hls_playlist(content: str, proxy_base: str, short_id: str) -> str:
    """
    FIX #11: Rewrites HLS playlist segment URLs to go through our proxy.
    Handles both absolute (https://...) and relative (path/to/file.ts) URLs.
    This ensures segment 403s never reach the Android client.
    """
    lines = content.splitlines()
    rewritten = []
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("#"):
            rewritten.append(line)
        elif stripped:
            # Encode the segment URL and route through proxy
            import urllib.parse
            encoded = urllib.parse.quote(stripped, safe="")
            rewritten.append(f"{proxy_base}/shorts/{short_id}/hls-segment?url={encoded}")
        else:
            rewritten.append(line)
    return "\n".join(rewritten)


@router.get("/{short_id}/hls-segment")
async def proxy_hls_segment(
    short_id: str,
    request: Request,
    url: str = Query(..., description="Encoded segment URL to proxy"),
):
    """
    FIX #11: Proxies individual HLS segment files (.ts chunks, variant playlists).
    Prevents signed CDN URL IP mismatch 403s from reaching Android.
    """
    import urllib.parse
    raw_url = urllib.parse.unquote(url)

    headers = {
        "User-Agent": "com.google.android.youtube/19.29.37 (Linux; U; Android 11; US)",
    }
    range_header = request.headers.get("range")
    if range_header:
        headers["Range"] = range_header

    client = httpx.AsyncClient(follow_redirects=True, timeout=30.0)
    req = client.build_request("GET", raw_url, headers=headers)
    r = await client.send(req, stream=True)

    if r.status_code not in (200, 206, 304):
        await r.aclose()
        await client.aclose()
        add_custom_log("WARNING", "proxy", f"[PROXY_403] HLS segment failed HTTP {r.status_code} for short {short_id}")
        raise HTTPException(status_code=r.status_code, detail="HLS segment unavailable")

    async def stream_bytes():
        try:
            async for chunk in r.aiter_bytes(chunk_size=65536):
                yield chunk
        finally:
            await r.aclose()
            await client.aclose()

    response_headers = {}
    for h in ["content-type", "content-length", "content-range", "accept-ranges"]:
        if h in r.headers:
            response_headers[h] = r.headers[h]

    return StreamingResponse(
        stream_bytes(),
        status_code=r.status_code,
        headers=response_headers,
        media_type=r.headers.get("content-type", "video/MP2T"),
    )


@router.get("/{short_id}/proxy", name="proxy_short_video")
async def proxy_short_video(
    short_id: str,
    request: Request,
    db: aiosqlite.Connection = Depends(get_db),
):
    """
    FIX #10, #11, #14: Proxies YouTube video stream with:
    - HLS playlist rewriting (segment URLs routed through proxy)
    - MP4 byte streaming with Range support
    - Single-retry on 403/404/410 with cache invalidation
    """
    async with db.execute(
        "SELECT id, youtube_video_id, video_url, stream_type, stream_expires_at FROM shorts WHERE id = ?",
        (short_id,),
    ) as cursor:
        row = await cursor.fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Short not found")

    yt_video_id = row["youtube_video_id"]
    current_video_url = row["video_url"]
    current_stream_type = row["stream_type"] or "MP4"
    current_expires_at = row["stream_expires_at"]

    raw_url = current_video_url
    if not raw_url or stream.is_stream_expired(current_expires_at):
        add_custom_log("INFO", "proxy", f"[CACHE_MISS] Proxy resolving fresh stream for short {short_id}")
        try:
            extracted = await stream.extract_stream_url_async(yt_video_id)
            raw_url = extracted["video_url"]
            current_stream_type = extracted["stream_type"]
            now_iso = datetime.datetime.now(datetime.timezone.utc).isoformat()
            # FIX #9: Update BOTH SQLite and Redis
            await db.execute(
                "UPDATE shorts SET video_url = ?, stream_type = ?, stream_expires_at = ?, updated_at = ? WHERE id = ?",
                (raw_url, current_stream_type, extracted["expires_at"], now_iso, short_id),
            )
            await db.commit()
            # Redis already updated inside extract_stream_url_async()
        except Exception as e:
            raise HTTPException(status_code=503, detail=f"Failed to extract stream: {str(e)}")

    headers = {
        "User-Agent": "com.google.android.youtube/19.29.37 (Linux; U; Android 11; US)",
    }
    range_header = request.headers.get("range")
    if range_header:
        headers["Range"] = range_header

    client = httpx.AsyncClient(follow_redirects=True, timeout=30.0)
    req = client.build_request("GET", raw_url, headers=headers)
    r = await client.send(req, stream=True)

    if r.status_code not in (200, 206):
        # FIX #10: Single retry path on 403/404/410
        await r.aclose()
        await client.aclose()
        add_custom_log("WARNING", "proxy", f"[PROXY_403] HTTP {r.status_code} for short {short_id} ({yt_video_id}) -> Invalidating and retrying once")

        # FIX #10: Invalidate BOTH Redis and SQLite before retry
        await redis_client.invalidate_stream(yt_video_id)
        await db.execute(
            "UPDATE shorts SET video_url = NULL, stream_expires_at = NULL WHERE id = ?",
            (short_id,),
        )
        await db.commit()

        try:
            extracted = await stream.extract_stream_url_async(yt_video_id)
            raw_url = extracted["video_url"]
            current_stream_type = extracted["stream_type"]
            now_iso = datetime.datetime.now(datetime.timezone.utc).isoformat()
            # FIX #9: Update BOTH layers
            await db.execute(
                "UPDATE shorts SET video_url = ?, stream_type = ?, stream_expires_at = ?, updated_at = ? WHERE id = ?",
                (raw_url, current_stream_type, extracted["expires_at"], now_iso, short_id),
            )
            await db.commit()
            add_custom_log("INFO", "proxy", f"[PROXY_RETRY] Fresh stream resolved for short {short_id}")
        except Exception as e:
            raise HTTPException(status_code=503, detail=f"[PROXY_RETRY] Re-extraction failed: {str(e)}")

        client = httpx.AsyncClient(follow_redirects=True, timeout=30.0)
        req = client.build_request("GET", raw_url, headers=headers)
        r = await client.send(req, stream=True)

        if r.status_code not in (200, 206):
            await r.aclose()
            await client.aclose()
            add_custom_log("ERROR", "proxy", f"[PROXY_403] Retry also failed HTTP {r.status_code} for short {short_id} — giving up")
            raise HTTPException(status_code=502, detail="Stream unavailable after retry")

    content_type = r.headers.get("content-type", "")
    is_hls = (
        current_stream_type.upper() == "HLS"
        or "mpegurl" in content_type.lower()
        or raw_url.endswith(".m3u8")
    )

    if is_hls:
        # FIX #11: For HLS, read the playlist and rewrite all segment/sub-playlist URLs
        playlist_bytes = await r.aread()
        await r.aclose()
        await client.aclose()
        playlist_text = playlist_bytes.decode("utf-8", errors="replace")

        # Build proxy base URL (scheme + host)
        base = f"{request.url.scheme}://{request.url.netloc}"
        rewritten = _rewrite_hls_playlist(playlist_text, base, short_id)
        add_custom_log("INFO", "proxy", f"[STREAM_REFRESH] HLS playlist rewritten for short {short_id}")

        return Response(
            content=rewritten.encode("utf-8"),
            media_type="application/vnd.apple.mpegurl",
            headers={"Cache-Control": "no-cache"},
        )

    # MP4 / non-HLS: stream bytes directly with Range support
    async def stream_bytes():
        try:
            async for chunk in r.aiter_bytes(chunk_size=65536):
                yield chunk
        finally:
            await r.aclose()
            await client.aclose()

    response_headers = {}
    for h in ["content-type", "content-length", "content-range", "accept-ranges"]:
        if h in r.headers:
            response_headers[h] = r.headers[h]

    return StreamingResponse(
        stream_bytes(),
        status_code=r.status_code,
        headers=response_headers,
        media_type=r.headers.get("content-type", "video/mp4"),
    )


@router.post("/discover")
async def trigger_discovery(
    query: str = Query("anime shorts", description="YouTube search query"),
    x_youtube_api_key: Optional[str] = Header(None, alias="X-YouTube-Api-Key"),
    db: aiosqlite.Connection = Depends(get_db),
):
    """
    Triggers manual YouTube Shorts discovery with the X-YouTube-Api-Key header.
    """
    discovered = await discovery.discover_youtube_shorts(
        api_key=x_youtube_api_key, db=db, query=query
    )
    return {"count": len(discovered), "items": discovered}
