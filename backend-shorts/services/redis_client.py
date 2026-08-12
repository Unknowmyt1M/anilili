import os
import json
import httpx
import logging
from typing import Optional, Dict, Any
from api.logs import add_custom_log

logger = logging.getLogger(__name__)


from dotenv import load_dotenv

load_dotenv()

def mask_sensitive(text: str) -> str:
    """Masks secret tokens and sensitive query params from logs."""
    if not text:
        return ""
    masked = text.replace(
        os.getenv("UPSTASH_REDIS_REST_TOKEN", "XYZ_SECRET"), "[MASKED_REDIS_TOKEN]"
    )
    return masked


class UpstashRedisClient:
    """
    Async REST Client for Upstash Redis.
    Provides stream caching, resolution status tracking, atomic locking, and prefetch queueing.
    Includes automatic graceful fallback if Redis is unavailable.
    """

    def __init__(self):
        load_dotenv()
        self.url = os.getenv("UPSTASH_REDIS_REST_URL", "").rstrip("/")
        self.token = os.getenv("UPSTASH_REDIS_REST_TOKEN", "")
        self.is_configured = bool(self.url and self.token)
        self.headers = {
            "Authorization": f"Bearer {self.token}",
            "Content-Type": "application/json",
        }

    async def _execute_command(self, command: list) -> Optional[Any]:
        """Executes a single Redis command via Upstash REST API with error handling."""
        if not self.is_configured:
            return None

        try:
            async with httpx.AsyncClient(timeout=4.0) as client:
                resp = await client.post(
                    f"{self.url}",
                    json=command,
                    headers=self.headers,
                )
                if resp.status_code == 200:
                    result_data = resp.json()
                    return result_data.get("result")
                else:
                    add_custom_log(
                        "WARNING",
                        "redis",
                        f"Upstash Redis REST error HTTP {resp.status_code}: {resp.text[:100]}",
                    )
                    return None
        except Exception as e:
            add_custom_log(
                "WARNING",
                "redis",
                f"Redis fallback active. Operation failed: {mask_sensitive(str(e))}",
            )
            return None

    async def check_health(self) -> bool:
        """Returns True if Upstash Redis ping succeeds."""
        if not self.is_configured:
            return False
        res = await self._execute_command(["PING"])
        return res == "PONG"

    async def get_stream(self, video_id: str) -> Optional[Dict[str, Any]]:
        """
        Fetches stream metadata dict from Redis key `anilili:stream:{video_id}`.
        Returns dict or None on miss/failure.
        """
        key = f"anilili:stream:{video_id}"
        res = await self._execute_command(["GET", key])
        if res:
            try:
                data = json.loads(res)
                add_custom_log("INFO", "redis", f"REDIS STREAM HIT for video: {video_id}")
                return data
            except Exception:
                pass
        return None

    async def set_stream(self, video_id: str, data: Dict[str, Any], ttl_seconds: int = 14400):
        """
        Caches stream metadata into `anilili:stream:{video_id}` with TTL.
        """
        key = f"anilili:stream:{video_id}"
        val_str = json.dumps(data)
        res = await self._execute_command(["SET", key, val_str, "EX", str(ttl_seconds)])
        if res:
            add_custom_log("INFO", "redis", f"REDIS STREAM SET for video: {video_id} (TTL: {ttl_seconds}s)")

    async def invalidate_stream(self, video_id: str):
        """Removes stream cache & status keys for video_id."""
        key = f"anilili:stream:{video_id}"
        status_key = f"anilili:stream:status:{video_id}"
        await self._execute_command(["DEL", key, status_key])
        add_custom_log("INFO", "redis", f"REDIS STREAM INVALIDATED for video: {video_id}")

    async def get_resolution_status(self, video_id: str) -> str:
        """
        Returns resolution status: PENDING, RESOLVING, READY, EXPIRED, FAILED.
        Defaults to PENDING if key not found.
        """
        key = f"anilili:stream:status:{video_id}"
        res = await self._execute_command(["GET", key])
        return res if res else "PENDING"

    async def set_resolution_status(self, video_id: str, status: str, ttl_seconds: int = 300):
        """Sets status (PENDING, RESOLVING, READY, EXPIRED, FAILED) with TTL."""
        key = f"anilili:stream:status:{video_id}"
        await self._execute_command(["SET", key, status, "EX", str(ttl_seconds)])

    async def acquire_resolution_lock(self, video_id: str, lock_ttl_seconds: int = 30) -> bool:
        """
        Atomic lock acquisition via `SET key value NX EX ttl`.
        Returns True if lock acquired, False if lock already held by another task.
        """
        key = f"anilili:stream:lock:{video_id}"
        res = await self._execute_command(["SET", key, "LOCKED", "NX", "EX", str(lock_ttl_seconds)])
        acquired = (res == "OK")
        if acquired:
            add_custom_log("INFO", "redis", f"REDIS LOCK ACQUIRED for video: {video_id}")
        else:
            add_custom_log("INFO", "redis", f"REDIS LOCK BUSY for video: {video_id} (another task in-flight)")
        return acquired

    async def release_resolution_lock(self, video_id: str):
        """Releases the resolution lock for video_id."""
        key = f"anilili:stream:lock:{video_id}"
        await self._execute_command(["DEL", key])


# Global singleton instance
redis_client = UpstashRedisClient()
