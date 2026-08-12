import os
import aiosqlite
from typing import AsyncGenerator

DATABASE_PATH = os.environ.get("DATABASE_PATH", "shorts.db")


async def get_db() -> AsyncGenerator[aiosqlite.Connection, None]:
    """Yields an aiosqlite connection configured with Row factory."""
    async with aiosqlite.connect(DATABASE_PATH) as db:
        db.row_factory = aiosqlite.Row
        yield db


async def init_db(db_path: str = DATABASE_PATH) -> None:
    """Initializes SQLite database schema for shorts and reactions tables."""
    async with aiosqlite.connect(db_path) as db:
        await db.execute("""
        CREATE TABLE IF NOT EXISTS shorts (
            id TEXT PRIMARY KEY,
            youtube_video_id TEXT UNIQUE NOT NULL,
            youtube_url TEXT NOT NULL,
            title TEXT,
            description TEXT,
            channel_name TEXT,
            thumbnail_url TEXT,
            anilist_id INTEGER,
            series_title TEXT,
            poster_url TEXT,
            episode_number INTEGER,
            video_url TEXT,
            stream_type TEXT DEFAULT 'MP4',
            stream_expires_at TEXT,
            like_count INTEGER DEFAULT 0,
            dislike_count INTEGER DEFAULT 0,
            resolution_method TEXT,
            resolution_confidence REAL DEFAULT 0.0,
            is_unavailable INTEGER DEFAULT 0,
            fail_count INTEGER DEFAULT 0,
            created_at TEXT,
            updated_at TEXT
        );
        """)

        await db.execute("""
        CREATE TABLE IF NOT EXISTS reactions (
            short_id TEXT NOT NULL,
            user_id TEXT NOT NULL,
            reaction TEXT NOT NULL CHECK(reaction IN ('LIKE','DISLIKE','NONE')),
            created_at TEXT,
            PRIMARY KEY (short_id, user_id)
        );
        """)

        await db.commit()
