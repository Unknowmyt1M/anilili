import aiosqlite
from typing import Optional, Dict, Any, List
from db.models import ShortItem, ShortsPage


async def get_ranked_shorts(
    db: aiosqlite.Connection,
    cursor: Optional[str] = None,
    limit: int = 10,
    user_id: Optional[str] = None,
) -> ShortsPage:
    """
    Fetches a page of available shorts ranked by popularity and freshness.
    Applies cursor-based pagination and attaches user's reaction state.
    """
    query = """
        SELECT
            id, youtube_video_id, youtube_url, title, description, channel_name,
            thumbnail_url, anilist_id, series_title, poster_url, episode_number,
            video_url, stream_type, stream_expires_at, like_count, dislike_count,
            resolution_method, resolution_confidence, is_unavailable, created_at,
            feed_position
        FROM shorts
        WHERE is_unavailable = 0 AND fail_count < 3

    """
    params: List[Any] = []

    if cursor:
        query += " AND feed_position < ?"
        params.append(float(cursor))

    query += " ORDER BY feed_position DESC, id DESC LIMIT ?"
    params.append(limit + 1)  # fetch 1 extra to check for next page

    async with db.execute(query, params) as stmt:
        rows = await stmt.fetchall()

    has_next = len(rows) > limit
    items_rows = rows[:limit]
    # FIX: Generate cursor from feed_position. If list has items, always return nextCursor
    # so client can continue pagination even if len(items_rows) < limit.
    next_cursor = str(items_rows[-1]["feed_position"]) if items_rows else None

    # Fetch user reactions if user_id is provided
    user_reactions = {}
    if user_id and items_rows:
        short_ids = [row["id"] for row in items_rows]
        placeholders = ",".join(["?"] * len(short_ids))
        reaction_query = f"SELECT short_id, reaction FROM reactions WHERE user_id = ? AND short_id IN ({placeholders})"
        async with db.execute(reaction_query, [user_id] + short_ids) as react_stmt:
            react_rows = await react_stmt.fetchall()
            for r in react_rows:
                user_reactions[r["short_id"]] = r["reaction"]

    items: List[ShortItem] = []
    for r in items_rows:
        sid = r["id"]
        reaction = user_reactions.get(sid, "NONE")
        items.append(
            ShortItem(
                id=sid,
                youtubeVideoId=r["youtube_video_id"],
                youtubeUrl=r["youtube_url"],
                title=r["title"],
                description=r["description"],
                channelName=r["channel_name"],
                thumbnailUrl=r["thumbnail_url"],
                anilistId=r["anilist_id"],
                seriesTitle=r["series_title"],
                posterUrl=r["poster_url"],
                episodeNumber=r["episode_number"],
                videoUrl=r["video_url"],
                streamType=r["stream_type"] or "MP4",
                streamExpiresAt=r["stream_expires_at"],
                likeCount=r["like_count"] or 0,
                dislikeCount=r["dislike_count"] or 0,
                userReaction=reaction,
                resolutionMethod=r["resolution_method"],
                resolutionConfidence=r["resolution_confidence"] or 0.0,
                isUnavailable=bool(r["is_unavailable"]),
                createdAt=r["created_at"],
            )
        )

    return ShortsPage(items=items, nextCursor=next_cursor)
