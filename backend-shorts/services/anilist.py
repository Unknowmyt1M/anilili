import httpx
import re
from typing import Optional, Dict, Any, List

ANILIST_GRAPHQL_URL = "https://graphql.anilist.co"

SEARCH_ANIME_QUERY = """
query ($search: String) {
  Media (search: $search, type: ANIME) {
    id
    title {
      romaji
      english
      native
    }
    synonyms
    coverImage {
      extraLarge
      large
    }
  }
}
"""

GET_ANIME_BY_ID_QUERY = """
query ($id: Int) {
  Media (id: $id, type: ANIME) {
    id
    title {
      romaji
      english
      native
    }
    synonyms
    coverImage {
      extraLarge
      large
    }
  }
}
"""


def _normalize(s: str) -> str:
    if not s:
        return ""
    # Lowercase, strip non-alphanumeric except spaces
    s = s.lower()
    s = re.sub(r"[^\w\s]", "", s)
    return re.sub(r"\s+", " ", s).strip()


def _similarity(s1: str, s2: str) -> float:
    """Computes basic token jaccard similarity between two strings."""
    n1 = _normalize(s1)
    n2 = _normalize(s2)
    if not n1 or not n2:
        return 0.0
    if n1 == n2:
        return 1.0
    tokens1 = set(n1.split())
    tokens2 = set(n2.split())
    if not tokens1 or not tokens2:
        return 0.0
    intersection = tokens1.intersection(tokens2)
    union = tokens1.union(tokens2)
    return len(intersection) / len(union)


async def search_anime_by_title(title: str) -> Optional[Dict[str, Any]]:
    """Searches AniList GraphQL API for anime matching title."""
    clean_query = _normalize(title)
    if not clean_query:
        return None

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.post(
                ANILIST_GRAPHQL_URL,
                json={"query": SEARCH_ANIME_QUERY, "variables": {"search": title}},
            )
            if response.status_code != 200:
                return None

            data = response.json()
            media = data.get("data", {}).get("Media")
            if not media:
                return None

            return media
    except Exception:
        return None


async def get_anime_by_id(anilist_id: int) -> Optional[Dict[str, Any]]:
    """Fetches anime details from AniList by ID."""
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.post(
                ANILIST_GRAPHQL_URL,
                json={"query": GET_ANIME_BY_ID_QUERY, "variables": {"id": anilist_id}},
            )
            if response.status_code != 200:
                return None

            data = response.json()
            return data.get("data", {}).get("Media")
    except Exception:
        return None


async def verify_anime_title(proposed_title: str) -> Optional[Dict[str, Any]]:
    """
    Verifies proposed anime title against AniList database.
    If verified, returns dict with anilist_id, series_title, poster_url.
    If AniList search yields no match or low confidence match, returns None.
    """
    if not proposed_title:
        return None

    media = await search_anime_by_title(proposed_title)
    if not media:
        return None

    # Check match against titles and synonyms
    title_obj = media.get("title", {})
    english = title_obj.get("english") or ""
    romaji = title_obj.get("romaji") or ""
    native = title_obj.get("native") or ""
    synonyms = media.get("synonyms") or []

    candidate_titles = [t for t in [english, romaji, native] + synonyms if t]
    best_sim = 0.0

    for candidate in candidate_titles:
        sim = _similarity(proposed_title, candidate)
        if sim > best_sim:
            best_sim = sim

    # Also check if proposed_title is substring of candidate or candidate is substring of proposed_title
    norm_proposed = _normalize(proposed_title)
    for candidate in candidate_titles:
        norm_cand = _normalize(candidate)
        if norm_cand and (norm_cand in norm_proposed or norm_proposed in norm_cand):
            if best_sim < 0.6:
                best_sim = 0.6

    if best_sim >= 0.4:
        display_title = english if english else romaji
        cover_image = media.get("coverImage", {})
        poster_url = cover_image.get("extraLarge") or cover_image.get("large") or ""
        return {
            "anilist_id": media["id"],
            "series_title": display_title,
            "poster_url": poster_url,
            "verification_confidence": max(best_sim, 0.85),
        }

    return None
