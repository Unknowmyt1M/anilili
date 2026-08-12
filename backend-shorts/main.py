import os
import time
from contextlib import asynccontextmanager
from dotenv import load_dotenv
from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse
from fastapi.middleware.cors import CORSMiddleware
from db.database import init_db
from api import shorts, reactions, youtube_key, logs, app_logs
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
app.include_router(app_logs.router)


from services.redis_client import redis_client

@app.get("/", response_class=HTMLResponse)
async def root():
    html_content = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Anilili Shorts API</title>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg: #080a12;
            --surface: #10131f;
            --border: #1e2235;
            --text: #e2e8f0;
            --muted: #94a3b8;
            --accent: #7c3aed;
            --accent-hover: #6d28d9;
            --blue: #3b82f6;
            --blue-hover: #2563eb;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            background-color: var(--bg);
            color: var(--text);
            font-family: 'Inter', sans-serif;
            display: flex;
            justify-content: center;
            align-items: center;
            height: 100vh;
        }
        .container {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 40px;
            text-align: center;
            max-width: 500px;
            width: 90%;
            box-shadow: 0 10px 30px -5px rgba(0,0,0,0.5);
        }
        .logo {
            font-size: 2.5rem;
            font-weight: 800;
            background: linear-gradient(135deg, #a78bfa, #60a5fa);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            margin-bottom: 8px;
        }
        .subtitle {
            color: var(--muted);
            font-size: 1rem;
            margin-bottom: 32px;
            line-height: 1.5;
        }
        .status-badge {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            background: rgba(16,185,129,0.15);
            color: #10b981;
            padding: 6px 12px;
            border-radius: 20px;
            font-size: 0.85rem;
            font-weight: 600;
            margin-bottom: 24px;
        }
        .status-dot {
            width: 8px;
            height: 8px;
            background: #10b981;
            border-radius: 50%;
            animation: pulse 2s infinite;
        }
        @keyframes pulse {
            0% { box-shadow: 0 0 0 0 rgba(16,185,129,0.4); }
            70% { box-shadow: 0 0 0 6px rgba(16,185,129,0); }
            100% { box-shadow: 0 0 0 0 rgba(16,185,129,0); }
        }
        .btn-group {
            display: flex;
            flex-direction: column;
            gap: 16px;
        }
        .btn {
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 10px;
            text-decoration: none;
            padding: 14px 20px;
            border-radius: 12px;
            font-weight: 600;
            font-size: 1rem;
            transition: all 0.2s ease;
        }
        .btn-primary {
            background: var(--accent);
            color: white;
            border: 1px solid var(--accent);
        }
        .btn-primary:hover {
            background: var(--accent-hover);
            transform: translateY(-2px);
            box-shadow: 0 4px 12px rgba(124,58,237,0.3);
        }
        .btn-secondary {
            background: rgba(59,130,246,0.1);
            color: var(--blue);
            border: 1px solid rgba(59,130,246,0.3);
        }
        .btn-secondary:hover {
            background: rgba(59,130,246,0.2);
            transform: translateY(-2px);
            box-shadow: 0 4px 12px rgba(59,130,246,0.15);
        }
        .footer {
            margin-top: 32px;
            font-size: 0.8rem;
            color: var(--muted);
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="status-badge">
            <div class="status-dot"></div>
            System Online
        </div>
        <div class="logo">Anilili Shorts API</div>
        <div class="subtitle">Backend Services & Log Management Dashboard for Anilili Anime App</div>
        
        <div class="btn-group">
            <a href="/app_logs" class="btn btn-primary">
                📱 View App Logs (Android)
            </a>
            <a href="/logs" class="btn btn-secondary">
                🖥️ View Server Logs (Backend)
            </a>
        </div>
        
        <div class="footer">
            Version 1.0.0 &bull; <a href="/health" style="color: var(--muted); text-decoration: underline;">Health Check</a>
        </div>
    </div>
</body>
</html>"""
    return HTMLResponse(content=html_content)


@app.get("/health")
async def health_check():
    redis_healthy = await redis_client.check_health()
    return {
        "status": "ok" if redis_healthy else "degraded",
        "redis": "connected" if redis_healthy else "unavailable",
        "version": "1.0.0",
    }

