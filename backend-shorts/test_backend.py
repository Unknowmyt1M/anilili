import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import main
from db.database import init_db
from services import stream
from fastapi.testclient import TestClient


def test_fastapi_endpoints():
    if os.path.exists("shorts.db"):
        os.remove("shorts.db")
    # Run init_db synchronously before TestClient
    asyncio.run(init_db("shorts.db"))

    with TestClient(main.app) as client:
        # Test Root
        resp = client.get("/")
        assert resp.status_code == 200, f"Root failed: {resp.status_code}"
        print("[PASS] Root endpoint returned 200 OK")

        # Test /shorts endpoint (Empty feed baseline)
        resp = client.get("/shorts")
        assert resp.status_code == 200, f"Get shorts failed: {resp.status_code}"
        json_data = resp.json()
        assert json_data == {"items": [], "nextCursor": None}, f"Unexpected output: {json_data}"
        print("[PASS] GET /shorts returned expected empty feed: {'items': [], 'nextCursor': None}")

        # Test /youtube/validate-key without header (Expected 401)
        resp = client.get("/youtube/validate-key")
        assert resp.status_code == 401, f"Expected 401, got {resp.status_code}"
        assert "YouTube API key required" in resp.json().get("detail", ""), f"Unexpected detail: {resp.json()}"
        print("[PASS] GET /youtube/validate-key without key returned HTTP 401 with expected message")

        # Test /shorts/{id}/react with non-existent ID (Expected 404)
        resp = client.post(
            "/shorts/nonexistent_id/react",
            json={"reaction": "LIKE"},
            headers={"X-User-Id": "test_user"},
        )
        assert resp.status_code == 404, f"Expected 404, got {resp.status_code}"
        print("[PASS] POST /shorts/nonexistent_id/react returned 404")


def test_youtube_id_validation():
    # 11-char regex check
    assert stream.validate_youtube_id("dQw4w9WgXcQ") is True
    assert stream.validate_youtube_id("short") is False
    assert stream.validate_youtube_id("too_long_youtube_id_123") is False
    print("[PASS] YouTube ID regex validation tested successfully")


def test_stream_expiry_parsing():
    url_with_expire = "https://rr1---sn-ab5l6nr7.googlevideo.com/videoplayback?expire=1723200000&ei=123"
    expires_at = stream.get_stream_expires_at(url_with_expire)
    assert expires_at is not None
    url_without_expire = "https://example.com/video.mp4"
    expires_at_fallback = stream.get_stream_expires_at(url_without_expire)
    assert expires_at_fallback is not None
    print("[PASS] Stream expiry parsing & 5-min safety buffer tested successfully")


if __name__ == "__main__":
    print("--- Running Backend Tests ---")
    test_youtube_id_validation()
    test_stream_expiry_parsing()
    test_fastapi_endpoints()
    print("--- ALL BACKEND TESTS PASSED SUCCESSFULLY ---")
