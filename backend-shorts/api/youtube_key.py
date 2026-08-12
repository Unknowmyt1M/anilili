import httpx
from typing import Optional
from fastapi import APIRouter, Header, HTTPException
from db.models import YouTubeKeyValidationResponse
from services.discovery import map_youtube_api_error

router = APIRouter(prefix="/youtube", tags=["YouTube API Key"])

YOUTUBE_VALIDATE_URL = "https://www.googleapis.com/youtube/v3/search"


@router.get("/validate-key", response_model=YouTubeKeyValidationResponse)
@router.post("/validate-key", response_model=YouTubeKeyValidationResponse)
async def validate_youtube_key(
    x_youtube_api_key: Optional[str] = Header(None, alias="X-YouTube-Api-Key")
):
    """
    Validates the YouTube Data API key supplied in the X-YouTube-Api-Key header.
    Never logs or echoes back the key.
    Maps 403 -> 429 quotaExceeded, 400 -> 401 keyInvalid, missing -> 401.
    """
    if not x_youtube_api_key or not x_youtube_api_key.strip():
        raise HTTPException(
            status_code=401,
            detail="YouTube API key required. Enter it in Settings."
        )

    params = {
        "part": "snippet",
        "q": "anime",
        "maxResults": 1,
        "key": x_youtube_api_key.strip(),
    }

    try:
        async with httpx.AsyncClient(timeout=8.0) as client:
            resp = await client.get(YOUTUBE_VALIDATE_URL, params=params)
            if resp.status_code == 200:
                return YouTubeKeyValidationResponse(
                    valid=True,
                    message="YouTube API key is valid"
                )

            try:
                err_json = resp.json()
            except Exception:
                err_json = None

            raise map_youtube_api_error(resp.status_code, err_json)
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(
            status_code=503,
            detail="YouTube API unavailable. Check your connection."
        )
