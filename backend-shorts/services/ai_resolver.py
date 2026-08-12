import os
import json
import re
import httpx
from abc import ABC, abstractmethod
from typing import Optional, Dict, Any, List


class AnimeResolutionAI(ABC):
    @abstractmethod
    async def resolve(
        self, title: str, description: str, comments: Optional[List[str]] = None
    ) -> Optional[Dict[str, Any]]:
        """
        Extracts anime series title and episode number using AI logic.
        Must return a dict: {"title": str, "episode": Optional[int], "confidence": float}
        or None if resolution failed.
        """
        pass


class GeminiResolutionAI(AnimeResolutionAI):
    def __init__(self):
        self.model_name = os.environ.get("GEMINI_MODEL", "gemini-2.0-flash")
        self.api_key = os.environ.get("GEMINI_API_KEY", "")

    async def resolve(
        self, title: str, description: str, comments: Optional[List[str]] = None
    ) -> Optional[Dict[str, Any]]:
        if not self.api_key:
            return None

        prompt = (
            "You are an anime identification assistant. Analyze the YouTube Short information below:\n"
            f"Title: {title}\n"
            f"Description: {description or ''}\n"
        )
        if comments:
            prompt += f"Top Comments: {' | '.join(comments[:5])}\n"

        prompt += (
            "\nTask: Identify the main anime series title and episode number (if mentioned or implied).\n"
            "Return ONLY a JSON object with this exact format:\n"
            '{"series_title": "Anime Name", "episode_number": 12 or null, "confidence": 0.85}\n'
            "If no clear anime is identified, set series_title to null and confidence to 0.0."
        )

        try:
            # First try google.generativeai if available, otherwise HTTP REST request
            try:
                import google.generativeai as genai

                genai.configure(api_key=self.api_key)
                model = genai.GenerativeModel(self.model_name)
                response = model.generate_content(prompt)
                text = response.text if response else ""
            except Exception:
                # Fallback to direct HTTP request to Gemini REST API
                url = f"https://generativelanguage.googleapis.com/v1beta/models/{self.model_name}:generateContent?key={self.api_key}"
                async with httpx.AsyncClient(timeout=12.0) as client:
                    resp = await client.post(
                        url,
                        json={"contents": [{"parts": [{"text": prompt}]}]},
                    )
                    if resp.status_code != 200:
                        return None
                    data = resp.json()
                    candidates = data.get("candidates", [])
                    if not candidates:
                        return None
                    text = (
                        candidates[0]
                        .get("content", {})
                        .get("parts", [{}])[0]
                        .get("text", "")
                    )

            if not text:
                return None

            # Clean JSON markdown formatting if present
            clean_text = text.strip()
            if clean_text.startswith("```json"):
                clean_text = clean_text[7:]
            if clean_text.startswith("```"):
                clean_text = clean_text[3:]
            if clean_text.endswith("```"):
                clean_text = clean_text[:-3]
            clean_text = clean_text.strip()

            result = json.loads(clean_text)
            series_title = result.get("series_title")
            if not series_title:
                return None

            episode_number = result.get("episode_number")
            if episode_number is not None:
                try:
                    episode_number = int(episode_number)
                except (ValueError, TypeError):
                    episode_number = None

            confidence = float(result.get("confidence", 0.70))

            return {
                "series_title": str(series_title),
                "episode_number": episode_number,
                "confidence": confidence,
            }
        except Exception:
            # Never log the API key or sensitive AI error details with keys
            return None
