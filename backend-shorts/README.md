# Anilili Shorts Service

Python/FastAPI backend service for the **Anilili Shorts** anime short-video discovery feed.

## Features

- **YouTube Shorts Discovery**: Searches YouTube Data API for anime shorts.
- **5-Step Anime Resolution Pipeline**:
  1. Title text regex/keyword extraction (confidence >= 0.80)
  2. Description & hashtag extraction (confidence >= 0.75)
  3. Deterministic AniList title search (confidence >= 0.85)
  4. Gemini AI fallback (`GEMINI_MODEL`, default `gemini-2.0-flash`) with mandatory AniList verification
  5. YouTube top comments AI fallback with mandatory AniList verification
- **Player-Compatible Stream Extraction**:
  - Validates 11-char YouTube Video IDs (`^[a-zA-Z0-9_-]{11}$`).
  - Prioritizes HLS format (`bestvideo[protocol=m3u8_native]+bestaudio[protocol=m3u8_native]/best[protocol=m3u8_native]`).
  - Fallback MP4 format (`bestvideo[ext=mp4][vcodec^=avc1]+bestaudio[ext=m4a]/bestvideo[ext=mp4]+bestaudio/best[ext=mp4]/best`).
- **Stream Expiry Handling**:
  - Parses `expire` Unix timestamp from YouTube URL.
  - Deducts 5-minute safety buffer.
  - Fallback to 1-hour conservative expiry if missing.
- **Fail Counter & Availability**:
  - Increments `fail_count` on stream extraction error.
  - Marks `is_unavailable = 1` when `fail_count >= 3` and stops serving.
- **Atomic Reaction State Machine**:
  - Anilili DB counts only (`LIKE`, `DISLIKE`, `NONE`).
  - No YouTube engagement counts used.

---

## Setup & Installation

### Prerequisites
- Python 3.9+

### Environment Configuration
Create a `.env` file from `.env.example`:

```bash
cp .env.example .env
```

Edit `.env`:
```env
GEMINI_API_KEY=your_gemini_api_key_here
GEMINI_MODEL=gemini-2.0-flash
DATABASE_PATH=shorts.db
```

### Install Dependencies
```bash
pip install -r requirements.txt
```

### Run Server
```bash
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

---

## API Endpoints

### 1. Shorts Feed
- **`GET /shorts`**
  - Headers: `X-User-Id` (optional), `X-YouTube-Api-Key` (optional for cached feed, required for initial discovery)
  - Query parameters: `cursor` (ISO date string), `limit` (default: 10)
  - Returns: `ShortsPage` (`{"items": [...], "nextCursor": "..."}`)

### 2. Stream Extraction
- **`GET /shorts/{short_id}/stream`**
  - Extracts/refreshes playable HLS or MP4 stream URL via `yt-dlp`.
  - Returns: `StreamResponse` (`{"shortId": "...", "videoUrl": "...", "streamType": "HLS"|"MP4", "expiresAt": "..."}`)

### 3. Reactions
- **`POST /shorts/{short_id}/react`**
  - Headers: `X-User-Id`
  - Body: `{"reaction": "LIKE" | "DISLIKE" | "NONE"}`
  - Returns: `ReactionResponse` (`{"shortId": "...", "likeCount": 1, "dislikeCount": 0, "userReaction": "LIKE"}`)

### 4. YouTube API Key Validation
- **`GET /youtube/validate-key` / `POST /youtube/validate-key`**
  - Headers: `X-YouTube-Api-Key`
  - Returns: `YouTubeKeyValidationResponse` (`{"valid": true, "message": "YouTube API key is valid"}`)

### 5. Manual Discovery Trigger
- **`POST /shorts/discover`**
  - Headers: `X-YouTube-Api-Key`
  - Query parameters: `query` (default: `"anime shorts"`)
  - Returns: `{"count": N, "items": [...]}`

---

## Verification

Test empty feed output:
```bash
curl http://localhost:8000/shorts
```
Expected response:
```json
{"items":[],"nextCursor":null}
```
