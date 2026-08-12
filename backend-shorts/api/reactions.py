import datetime
import aiosqlite
from typing import Optional
from fastapi import APIRouter, Depends, Header, HTTPException, status
from db.database import get_db
from db.models import ReactionRequest, ReactionResponse

router = APIRouter(prefix="/shorts", tags=["Short Reactions"])


@router.post("/{short_id}/react", response_model=ReactionResponse)
async def react_to_short(
    short_id: str,
    payload: ReactionRequest,
    x_user_id: Optional[str] = Header("anonymous", alias="X-User-Id"),
    db: aiosqlite.Connection = Depends(get_db),
):
    """
    Applies reaction state machine atomically (NONE, LIKE, DISLIKE).
    Calculates exact Anilili DB counts only.
    """
    new_reaction = payload.reaction.upper()
    if new_reaction not in ("LIKE", "DISLIKE", "NONE"):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid reaction. Allowed values: LIKE, DISLIKE, NONE",
        )

    user_id = x_user_id or "anonymous"

    # Verify short exists
    async with db.execute(
        "SELECT id, like_count, dislike_count FROM shorts WHERE id = ?", (short_id,)
    ) as cursor:
        short_row = await cursor.fetchone()
        if not short_row:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Short with id '{short_id}' not found",
            )

    # Fetch previous reaction for (short_id, user_id)
    async with db.execute(
        "SELECT reaction FROM reactions WHERE short_id = ? AND user_id = ?",
        (short_id, user_id),
    ) as cursor:
        react_row = await cursor.fetchone()
        prev_reaction = react_row["reaction"] if react_row else "NONE"

    if prev_reaction == new_reaction:
        # No state change needed
        return ReactionResponse(
            shortId=short_id,
            likeCount=short_row["like_count"] or 0,
            dislikeCount=short_row["dislike_count"] or 0,
            userReaction=new_reaction,
        )

    # Compute deltas for atomic state machine
    like_delta = 0
    dislike_delta = 0

    if prev_reaction == "NONE" and new_reaction == "LIKE":
        like_delta = 1
    elif prev_reaction == "NONE" and new_reaction == "DISLIKE":
        dislike_delta = 1
    elif prev_reaction == "LIKE" and new_reaction == "NONE":
        like_delta = -1
    elif prev_reaction == "LIKE" and new_reaction == "DISLIKE":
        like_delta = -1
        dislike_delta = 1
    elif prev_reaction == "DISLIKE" and new_reaction == "NONE":
        dislike_delta = -1
    elif prev_reaction == "DISLIKE" and new_reaction == "LIKE":
        dislike_delta = -1
        like_delta = 1

    now_iso = datetime.datetime.now(datetime.timezone.utc).isoformat()

    # Update reactions table
    if new_reaction == "NONE":
        await db.execute(
            "DELETE FROM reactions WHERE short_id = ? AND user_id = ?",
            (short_id, user_id),
        )
    else:
        await db.execute(
            """
            INSERT INTO reactions (short_id, user_id, reaction, created_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(short_id, user_id) DO UPDATE SET reaction = excluded.reaction
            """,
            (short_id, user_id, new_reaction, now_iso),
        )

    # Update shorts like/dislike counts (non-negative floor)
    await db.execute(
        """
        UPDATE shorts
        SET like_count = MAX(0, like_count + ?),
            dislike_count = MAX(0, dislike_count + ?),
            updated_at = ?
        WHERE id = ?
        """,
        (like_delta, dislike_delta, now_iso, short_id),
    )
    await db.commit()

    # Fetch updated counts
    async with db.execute(
        "SELECT like_count, dislike_count FROM shorts WHERE id = ?", (short_id,)
    ) as cursor:
        updated_row = await cursor.fetchone()
        new_like_count = updated_row["like_count"] if updated_row else 0
        new_dislike_count = updated_row["dislike_count"] if updated_row else 0

    return ReactionResponse(
        shortId=short_id,
        likeCount=new_like_count,
        dislikeCount=new_dislike_count,
        userReaction=new_reaction,
    )
