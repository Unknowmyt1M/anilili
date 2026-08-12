from typing import Optional, List
from pydantic import BaseModel, Field


class ShortItem(BaseModel):
    id: str
    youtubeVideoId: str
    youtubeUrl: str
    title: Optional[str] = None
    description: Optional[str] = None
    channelName: Optional[str] = None
    thumbnailUrl: Optional[str] = None
    anilistId: Optional[int] = None
    seriesTitle: Optional[str] = None
    posterUrl: Optional[str] = None
    episodeNumber: Optional[int] = None
    videoUrl: Optional[str] = None
    streamType: str = "MP4"
    streamExpiresAt: Optional[str] = None
    likeCount: int = 0
    dislikeCount: int = 0
    userReaction: str = "NONE"
    resolutionMethod: Optional[str] = None
    resolutionConfidence: float = 0.0
    isUnavailable: bool = False
    createdAt: Optional[str] = None

    class Config:
        populate_by_name = True


class ShortsPage(BaseModel):
    items: List[ShortItem]
    nextCursor: Optional[str] = None


class StreamResponse(BaseModel):
    shortId: str
    streamStatus: str = "READY"  # READY, RESOLVING, EXPIRED, FAILED
    videoUrl: Optional[str] = None
    streamType: str = "MP4"
    expiresAt: Optional[str] = None


class ReactionRequest(BaseModel):
    reaction: str = Field(..., description="Reaction type: LIKE, DISLIKE, or NONE")


class ReactionResponse(BaseModel):
    shortId: str
    likeCount: int
    dislikeCount: int
    userReaction: str


class YouTubeKeyValidationResponse(BaseModel):
    valid: bool
    message: str
