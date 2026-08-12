import datetime
import aiosqlite
from typing import Optional, List
from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, status
from db.database import get_db
from db.models import ShortsPage, StreamResponse
from services import ranker, stream, discovery

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
            # Re-raise explicit API key exceptions
            raise
        except Exception:
            pass

    return page


from api.logs import add_custom_log
from services.redis_client import redis_client

@router.get("/{short_id}/stream", response_model=StreamResponse)
async def get_short_stream(
    short_id: str,
    request: Request,
    db: aiosqlite.Connection = Depends(get_db),
):
    """
    Extracts or refreshes playable stream URL for a short via Upstash Redis & yt-dlp resolver.
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

    # Ensure stream is warm in DB/Redis
    redis_stream = await redis_client.get_stream(yt_video_id)
    if not (redis_stream and not stream.is_stream_expired(redis_stream.get("expires_at"))):
        if not (current_video_url and not stream.is_stream_expired(current_expires_at)):
            try:
                add_custom_log("INFO", "stream", f"Cache miss/expired for short {short_id} ({yt_video_id}) -> Resolving...")
                extracted = await stream.extract_stream_url_async(yt_video_id)
                new_video_url = extracted["video_url"]
                new_stream_type = extracted["stream_type"]
                new_expires_at = extracted["expires_at"]
                now_iso = datetime.datetime.now(datetime.timezone.utc).isoformat()

                await db.execute(
                    """
                    UPDATE shorts
                    SET video_url = ?, stream_type = ?, stream_expires_at = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    (new_video_url, new_stream_type, new_expires_at, now_iso, short_id),
                )
                await db.commit()
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

                add_custom_log("ERROR", "stream", f"Resolution failed for short {short_id} ({yt_video_id}): {str(e)}")
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
    Invalidates cached stream in Redis and SQLite when ExoPlayer reports a stale/expired link.
    """
    async with db.execute("SELECT youtube_video_id FROM shorts WHERE id = ?", (short_id,)) as cursor:
        row = await cursor.fetchone()
        if row:
            yt_video_id = row["youtube_video_id"]
            await redis_client.invalidate_stream(yt_video_id)
            await db.execute(
                "UPDATE shorts SET video_url = NULL, stream_expires_at = NULL WHERE id = ?",
                (short_id,),
            )
            await db.commit()
            add_custom_log("INFO", "stream", f"Invalidated cached stream for short {short_id} ({yt_video_id})")
    return {"status": "invalidated"}


from fastapi import Request
from fastapi.responses import StreamingResponse
import httpx

@router.get("/{short_id}/proxy")
async def proxy_short_video(
    short_id: str,
    request: Request,
    db: aiosqlite.Connection = Depends(get_db),
):
    """
    Proxies YouTube video stream directly to Android app with HTTP Range support.
    Eliminates 403 Forbidden client IP mismatch errors.
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
    current_expires_at = row["stream_expires_at"]

    raw_url = current_video_url
    if not raw_url or stream.is_stream_expired(current_expires_at):
        try:
            extracted = await stream.extract_stream_url_async(yt_video_id)
            raw_url = extracted["video_url"]
            now_iso = datetime.datetime.now(datetime.timezone.utc).isoformat()
            await db.execute(
                "UPDATE shorts SET video_url = ?, stream_type = ?, stream_expires_at = ?, updated_at = ? WHERE id = ?",
                (raw_url, extracted["stream_type"], extracted["expires_at"], now_iso, short_id),
            )
            await db.commit()
        except Exception as e:
            raise HTTPException(status_code=503, detail=f"Failed to extract stream: {str(e)}")

    headers = {}
    range_header = request.headers.get("range")
    if range_header:
        headers["Range"] = range_header
    headers["User-Agent"] = "com.google.android.youtube/19.29.37 (Linux; U; Android 11; US)"

    client = httpx.AsyncClient(follow_redirects=True, timeout=30.0)
    req = client.build_request("GET", raw_url, headers=headers)
    r = await client.send(req, stream=True)

    if r.status_code not in (200, 206):
        await r.aclose()
        await client.aclose()
        # Invalidate and retry once if 403/410
        await redis_client.invalidate_stream(yt_video_id)
        extracted = await stream.extract_stream_url_async(yt_video_id)
        raw_url = extracted["video_url"]
        now_iso = datetime.datetime.now(datetime.timezone.utc).isoformat()
        await db.execute(
            "UPDATE shorts SET video_url = ?, stream_type = ?, stream_expires_at = ?, updated_at = ? WHERE id = ?",
            (raw_url, extracted["stream_type"], extracted["expires_at"], now_iso, short_id),
        )
        await db.commit()

        client = httpx.AsyncClient(follow_redirects=True, timeout=30.0)
        req = client.build_request("GET", raw_url, headers=headers)
        r = await client.send(req, stream=True)

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

