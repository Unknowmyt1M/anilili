import re
import httpx
from typing import Optional, Dict, Any, List
from services import anilist
from services.ai_resolver import GeminiResolutionAI

EPISODE_REGEXES = [
    r"(?:ep|episode|s\d+e|e)\.?\s*(\d+)",
    r"\[(\d{1,3})\]",
    r"\bep\s*(\d+)\b",
    r"episode\s*(\d+)",
]


def extract_episode_number(text: str) -> Optional[int]:
    """Extracts episode number from title or description using regex patterns."""
    if not text:
        return None
    for pattern in EPISODE_REGEXES:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            try:
                ep = int(match.group(1))
                if 1 <= ep <= 2000:
                    return ep
            except (ValueError, TypeError):
                continue
    return None


def clean_title_for_search(title: str) -> str:
    """Removes common noise words and tags from YouTube Shorts title."""
    if not title:
        return ""
    t = title
    # Remove hashtags
    t = re.sub(r"#\w+", "", t)
    # Remove brackets
    t = re.sub(r"\[.*?\]|\(.*?\)", "", t)
    # Remove noisy buzzwords
    noise = [
        r"\bshorts\b",
        r"\bshort\b",
        r"\banime\b",
        r"\bedit\b",
        r"\bamv\b",
        r"\b4k\b",
        r"\bhd\b",
        r"\bstatus\b",
        r"\breaction\b",
        r"\bclip\b",
        r"\bscene\b",
        r"\bviral\b",
    ]
    for n in noise:
        t = re.sub(n, "", t, flags=re.IGNORECASE)
    # Strip emojis and special characters
    t = re.sub(r"[^\w\s]", "", t)
    return re.sub(r"\s+", " ", t).strip()


async def fetch_youtube_comments_ytfetcher(video_id: str) -> List[str]:
    """Fetches YouTube comments via ytfetcher fallback without requiring an API key."""
    try:
        from ytfetcher import YTFetcher
        import asyncio
        fetcher = YTFetcher.from_video_ids([video_id])
        loop = asyncio.get_event_loop()
        data = await loop.run_in_executor(None, lambda: fetcher.fetch_with_comments(max_comments=10))
        comments = []
        for v in data:
            for c in v.comments:
                if c.text:
                    comments.append(c.text)
        return comments
    except Exception as e:
        add_custom_log("WARNING", "resolver", f"ytfetcher comment fetch error: {str(e)}")
        return []


async def fetch_youtube_comments(
    video_id: str, api_key: Optional[str] = None
) -> List[str]:
    """Fetches top comments using ytfetcher as primary engine, fallback to YouTube Data API if needed."""
    if not video_id:
        return []

    # Primary: ytfetcher (Zero API key / zero quota usage)
    comments = await fetch_youtube_comments_ytfetcher(video_id)

    # Secondary Fallback: YouTube Data API v3
    if not comments and api_key:
        url = f"https://www.googleapis.com/youtube/v3/commentThreads?part=snippet&videoId={video_id}&maxResults=10&order=relevance&key={api_key}"
        try:
            async with httpx.AsyncClient(timeout=8.0) as client:
                resp = await client.get(url)
                if resp.status_code == 200:
                    data = resp.json()
                    for item in data.get("items", []):
                        text = (
                            item.get("snippet", {})
                            .get("topLevelComment", {})
                            .get("snippet", {})
                            .get("textDisplay", "")
                        )
                        if text:
                            comments.append(text)
        except Exception:
            pass

    return comments


async def resolve_anime_short(
    title: str,
    description: Optional[str] = None,
    youtube_video_id: Optional[str] = None,
    youtube_api_key: Optional[str] = None,
) -> Dict[str, Any]:
    """
    Executes the 5-step anime resolution pipeline in strict order:
    1. Title text extraction (regex/keywords) -> confidence >= 0.80
    2. Description + hashtag extraction -> confidence >= 0.75
    3. Deterministic AniList title search (exact/synonym) -> confidence >= 0.85
    4. Gemini AI fallback (ONLY if 1-3 failed)
    5. YouTube comments fallback (ONLY if 1-4 all failed)

    Applies strict AniList verification for AI results and confidence thresholds:
    - >= 0.90 -> episode link + watch button
    - 0.75 - 0.89 -> series link
    - 0.50 - 0.74 -> uncertain (no watch button)
    - < 0.50 -> unresolved
    """
    desc = description or ""
    ep_number = extract_episode_number(title) or extract_episode_number(desc)

    resolution_method = "UNRESOLVED"
    confidence = 0.0
    anilist_id = None
    series_title = None
    poster_url = None

    # Step 1: Title text extraction
    clean_t = clean_title_for_search(title)
    if clean_t:
        anilist_res = await anilist.verify_anime_title(clean_t)
        if anilist_res and anilist_res.get("verification_confidence", 0) >= 0.5:
            resolution_method = "TITLE_REGEX"
            confidence = 0.85 if ep_number is None else 0.92
            anilist_id = anilist_res["anilist_id"]
            series_title = anilist_res["series_title"]
            poster_url = anilist_res["poster_url"]

    # Step 2: Description + hashtag extraction (if Step 1 failed)
    if not anilist_id and desc:
        hashtags = re.findall(r"#(\w+)", desc)
        for ht in hashtags:
            if ht.lower() in ("shorts", "anime", "edit", "fyp", "viral"):
                continue
            ht_clean = re.sub(r"([a-z])([A-Z])", r"\1 \2", ht)  # camelCase split
            anilist_res = await anilist.verify_anime_title(ht_clean)
            if anilist_res and anilist_res.get("verification_confidence", 0) >= 0.5:
                resolution_method = "DESCRIPTION_HASHTAG"
                confidence = 0.78 if ep_number is None else 0.90
                anilist_id = anilist_res["anilist_id"]
                series_title = anilist_res["series_title"]
                poster_url = anilist_res["poster_url"]
                break

    # Step 3: Deterministic AniList title search (if Step 1 & 2 failed)
    if not anilist_id:
        direct_media = await anilist.search_anime_by_title(title)
        if direct_media:
            anilist_res = await anilist.verify_anime_title(
                direct_media.get("title", {}).get("english")
                or direct_media.get("title", {}).get("romaji")
            )
            if anilist_res:
                resolution_method = "ANILIST_SEARCH"
                confidence = 0.85 if ep_number is None else 0.91
                anilist_id = anilist_res["anilist_id"]
                series_title = anilist_res["series_title"]
                poster_url = anilist_res["poster_url"]

    # Step 4: Gemini AI fallback (ONLY if Steps 1-3 failed)
    ai_service = GeminiResolutionAI()
    if not anilist_id:
        ai_res = await ai_service.resolve(title, desc, None)
        if ai_res and ai_res.get("series_title"):
            proposed = ai_res["series_title"]
            ai_ep = ai_res.get("episode_number") or ep_number
            # CRITICAL: Every AI result MUST be verified against AniList before use
            verified = await anilist.verify_anime_title(proposed)
            if verified:
                resolution_method = "GEMINI_AI"
                confidence = min(
                    ai_res.get("confidence", 0.8),
                    verified.get("verification_confidence", 0.85),
                )
                anilist_id = verified["anilist_id"]
                series_title = verified["series_title"]
                poster_url = verified["poster_url"]
                ep_number = ai_ep
            else:
                # AniList verification failed -> discard AI result, set anilist_id = null
                anilist_id = None
                series_title = None
                poster_url = None

    # Step 5: YouTube comments fallback (ONLY if Steps 1-4 all failed)
    if not anilist_id and youtube_video_id and youtube_api_key:
        comments = await fetch_youtube_comments(youtube_video_id, youtube_api_key)
        if comments:
            ai_res = await ai_service.resolve(title, desc, comments)
            if ai_res and ai_res.get("series_title"):
                proposed = ai_res["series_title"]
                ai_ep = ai_res.get("episode_number") or ep_number
                # CRITICAL: Every AI result MUST be verified against AniList before use
                verified = await anilist.verify_anime_title(proposed)
                if verified:
                    resolution_method = "YOUTUBE_COMMENTS_AI"
                    confidence = min(
                        ai_res.get("confidence", 0.75),
                        verified.get("verification_confidence", 0.85),
                    )
                    anilist_id = verified["anilist_id"]
                    series_title = verified["series_title"]
                    poster_url = verified["poster_url"]
                    ep_number = ai_ep
                else:
                    # Verification failed -> discard
                    anilist_id = None
                    series_title = None
                    poster_url = None

    # Apply Confidence Thresholds
    if confidence >= 0.90:
        # Show episode link
        return {
            "anilist_id": anilist_id,
            "series_title": series_title,
            "poster_url": poster_url,
            "episode_number": ep_number,
            "resolution_method": resolution_method,
            "resolution_confidence": confidence,
        }
    elif 0.75 <= confidence < 0.90:
        # Show series link only (episode_number = None)
        return {
            "anilist_id": anilist_id,
            "series_title": series_title,
            "poster_url": poster_url,
            "episode_number": None,
            "resolution_method": resolution_method,
            "resolution_confidence": confidence,
        }
    elif 0.50 <= confidence < 0.75:
        # Uncertain (no watch button) -> anilist_id = None
        return {
            "anilist_id": None,
            "series_title": series_title,
            "poster_url": poster_url,
            "episode_number": None,
            "resolution_method": resolution_method,
            "resolution_confidence": confidence,
        }
    else:
        # Unresolved
        return {
            "anilist_id": None,
            "series_title": None,
            "poster_url": None,
            "episode_number": None,
            "resolution_method": "UNRESOLVED",
            "resolution_confidence": 0.0,
        }
