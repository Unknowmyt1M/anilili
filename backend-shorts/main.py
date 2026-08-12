import os
import time
from contextlib import asynccontextmanager
from dotenv import load_dotenv
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from db.database import init_db
from api import shorts, reactions, youtube_key, logs
from api.logs import add_custom_log

load_dotenv()


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Initialize SQLite database tables on startup
    await init_db()
    add_custom_log("INFO", "server", "Anilili Shorts API started successfully.")
    yield


app = FastAPI(
    title="Anilili Shorts API",
    description="Backend Shorts Service for Anilili Anime Short-Video Feed",
    version="1.0.0",
    lifespan=lifespan,
)

# CORS middleware for Android app / client access
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def log_requests(request: Request, call_next):
    start_time = time.time()
    try:
        response = await call_next(request)
        process_time = int((time.time() - start_time) * 1000)
        client_host = request.client.host if request.client else "unknown"
        if not request.url.path.startswith("/api/logs") and not request.url.path == "/logs":
            add_custom_log(
                "INFO" if response.status_code < 400 else "ERROR",
                "http",
                f"{request.method} {request.url.path} -> {response.status_code} ({process_time}ms) from {client_host}"
            )
        return response
    except Exception as e:
        process_time = int((time.time() - start_time) * 1000)
        add_custom_log("ERROR", "http", f"Unhandled Exception on {request.method} {request.url.path}: {str(e)} ({process_time}ms)")
        raise e


# Register API Routers
app.include_router(shorts.router)
app.include_router(reactions.router)
app.include_router(youtube_key.router)
app.include_router(logs.router)


from services.redis_client import redis_client

@app.get("/")
async def root():
    return {
        "service": "Anilili Shorts API",
        "status": "ok",
        "version": "1.0.0",
        "logs_url": "/logs",
        "health_url": "/health",
    }


@app.get("/health")
async def health_check():
    redis_healthy = await redis_client.check_health()
    return {
        "status": "ok" if redis_healthy else "degraded",
        "redis": "connected" if redis_healthy else "unavailable",
        "version": "1.0.0",
    }

