import uuid
import datetime
import httpx
import aiosqlite
from typing import List, Dict, Any, Optional
from fastapi import HTTPException
from services.stream import validate_youtube_id
from services.resolver import resolve_anime_short

YOUTUBE_SEARCH_URL = "https://www.googleapis.com/youtube/v3/search"


def map_youtube_api_error(status_code: int, response_json: Optional[Dict[str, Any]]) -> HTTPException:
    """Maps YouTube Data API error responses to canonical FastAPI HTTPExceptions."""
    if status_code == 403:
        error_reason = ""
        if response_json and "error" in response_json:
            errors = response_json["error"].get("errors", [])
            if errors:
                error_reason = errors[0].get("reason", "")
        if "quota" in error_reason.lower() or "quotaexceeded" in error_reason.lower():
            return HTTPException(
                status_code=429,
                detail="YouTube API quota exceeded. Try again later."
            )
        return HTTPException(
            status_code=429,
            detail="YouTube API quota exceeded. Try again later."
        )
    elif status_code == 400:
        return HTTPException(
            status_code=401,
            detail="Invalid YouTube API key. Check Settings."
        )
    else:
        return HTTPException(
            status_code=503,
            detail="YouTube API unavailable. Check your connection."
        )


from api.logs import add_custom_log

async def discover_via_ytfetcher(query: str = "anime shorts", limit: int = 15) -> List[Dict[str, Any]]:
    """Fetches YouTube Shorts via ytfetcher without requiring a YouTube Data API key."""
    try:
        from ytfetcher import YTFetcher
        from ytfetcher.config import FetchOptions

        options = FetchOptions(max_concurrent_requests=5)
        fetcher = YTFetcher.from_search(query=query, max_results=limit, options=options)

        import asyncio
        loop = asyncio.get_event_loop()
        channel_data_list = await loop.run_in_executor(None, fetcher.fetch_youtube_data)

        items = []
        for cd in channel_data_list:
            meta = cd.metadata
            if not meta or not meta.video_id:
                continue
            thumb = ""
            if meta.thumbnails and isinstance(meta.thumbnails, list):
                thumb = meta.thumbnails[-1].get("url", "")
            items.append({
                "id": {"videoId": meta.video_id},
                "snippet": {
                    "title": meta.title or "",
                    "description": meta.description or "",
                    "channelTitle": getattr(meta, "channel_title", "") or "",
                    "thumbnails": {"high": {"url": thumb}}
                }
            })
        add_custom_log("INFO", "discovery", f"ytfetcher successfully fetched {len(items)} items")
        return items
    except Exception as e:
        add_custom_log("ERROR", "discovery", f"ytfetcher discovery error: {str(e)}")
        return []


async def discover_youtube_shorts(
    api_key: Optional[str], db: aiosqlite.Connection, query: str = "anime shorts"
) -> List[Dict[str, Any]]:
    """
    Discovers anime YouTube Shorts using ytfetcher as primary engine.
    Falls back to YouTube Data API v3 if ytfetcher returns no results and api_key is present.
    """
    add_custom_log("INFO", "discovery", f"Searching YouTube Shorts via primary ytfetcher engine query: '{query}'")
    items = await discover_via_ytfetcher(query, limit=15)

    if not items and api_key:
        add_custom_log("INFO", "discovery", f"ytfetcher returned no results, trying YouTube Data API fallback for query: '{query}'")
        params = {
            "part": "snippet",
            "q": query,
            "type": "video",
            "videoDuration": "short",
            "maxResults": 15,
            "key": api_key,
        }
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                resp = await client.get(YOUTUBE_SEARCH_URL, params=params)
                if resp.status_code == 200:
                    data = resp.json()
                    items = data.get("items", [])
                else:
                    add_custom_log("WARNING", "discovery", f"YouTube API fallback status {resp.status_code}")
        except Exception as e:
            add_custom_log("WARNING", "discovery", f"YouTube API fallback error ({str(e)})")

    items = data.get("items", [])
    add_custom_log("INFO", "discovery", f"YouTube Search API returned {len(items)} raw items")
    discovered = []
    now_iso = datetime.datetime.now(datetime.timezone.utc).isoformat()

    for item in items:
        id_info = item.get("id", {})
        video_id = id_info.get("videoId")
        if not video_id or not validate_youtube_id(video_id):
            continue

        snippet = item.get("snippet", {})
        title = snippet.get("title", "")
        description = snippet.get("description", "")
        channel_name = snippet.get("channelTitle", "")
        thumbnails = snippet.get("thumbnails", {})
        thumb_url = (
            thumbnails.get("high", {}).get("url")
            or thumbnails.get("medium", {}).get("url")
            or thumbnails.get("default", {}).get("url", "")
        )

        youtube_url = f"https://www.youtube.com/watch?v={video_id}"
        short_id = f"short_{uuid.uuid4().hex[:12]}"

        # Run 5-step resolution pipeline
        resolution = await resolve_anime_short(
            title=title,
            description=description,
            youtube_video_id=video_id,
            youtube_api_key=api_key,
        )

        # Check if already exists in DB
        async with db.execute(
            "SELECT id FROM shorts WHERE youtube_video_id = ?", (video_id,)
        ) as cursor:
            row = await cursor.fetchone()
            if row:
                continue

        # Insert new short into DB
        await db.execute(
            """
            INSERT OR IGNORE INTO shorts (
                id, youtube_video_id, youtube_url, title, description, channel_name,
                thumbnail_url, anilist_id, series_title, poster_url, episode_number,
                video_url, stream_type, stream_expires_at, like_count, dislike_count,
                resolution_method, resolution_confidence, is_unavailable, fail_count,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                short_id,
                video_id,
                youtube_url,
                title,
                description,
                channel_name,
                thumb_url,
                resolution.get("anilist_id"),
                resolution.get("series_title"),
                resolution.get("poster_url"),
                resolution.get("episode_number"),
                None,  # video_url extracted on demand
                "MP4",
                None,
                0,
                0,
                resolution.get("resolution_method", "UNRESOLVED"),
                resolution.get("resolution_confidence", 0.0),
                0,
                0,
                now_iso,
                now_iso,
            ),
        )
        await db.commit()

        discovered.append({
            "id": short_id,
            "youtubeVideoId": video_id,
            "title": title,
            "seriesTitle": resolution.get("series_title"),
            "anilistId": resolution.get("anilist_id"),
        })

    return discovered
