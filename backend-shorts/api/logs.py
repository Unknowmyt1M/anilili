import time
import sys
import logging
from collections import deque
from datetime import datetime
from typing import List, Optional
from fastapi import APIRouter, Request, Response
from fastapi.responses import HTMLResponse, JSONResponse

router = APIRouter(tags=["Logs"])

# Circular buffer to store up to 1000 logs in memory
MAX_LOG_ENTRIES = 1000
LOG_BUFFER: deque = deque(maxlen=MAX_LOG_ENTRIES)


class MemoryLogHandler(logging.Handler):
    """Custom logging handler to record log records into LOG_BUFFER for the web /logs UI."""
    def emit(self, record: logging.LogRecord):
        try:
            timestamp = datetime.fromtimestamp(record.created).strftime("%Y-%m-%d %H:%M:%S")
            log_entry = {
                "timestamp": timestamp,
                "level": record.levelname,
                "source": record.name,
                "message": record.getMessage(),
            }
            LOG_BUFFER.appendleft(log_entry)
        except Exception:
            self.handleError(record)


# Configure logger & attach MemoryLogHandler
memory_handler = MemoryLogHandler()
memory_handler.setLevel(logging.INFO)
root_logger = logging.getLogger()
root_logger.addHandler(memory_handler)
root_logger.setLevel(logging.INFO)


def add_custom_log(level: str, source: str, message: str):
    """Helper to manually push logs into the buffer."""
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    LOG_BUFFER.appendleft({
        "timestamp": timestamp,
        "level": level.upper(),
        "source": source,
        "message": message,
    })


class LogStreamWrapper:
    """Interceptors for sys.stdout and sys.stderr to capture all Python print statements."""
    def __init__(self, original_stream, source_name="stdout"):
        self.original_stream = original_stream
        self.source_name = source_name

    def write(self, message):
        if self.original_stream:
            self.original_stream.write(message)
        msg = message.strip()
        if msg and not msg.startswith("INFO:") and not msg.startswith("ERROR:"):
            level = "ERROR" if self.source_name == "stderr" else "INFO"
            add_custom_log(level, self.source_name, msg)

    def flush(self):
        if self.original_stream:
            self.original_stream.flush()


# Redirect standard stdout & stderr so all print() calls and output are captured
sys.stdout = LogStreamWrapper(sys.stdout, "stdout")
sys.stderr = LogStreamWrapper(sys.stderr, "stderr")


@router.get("/api/logs")
async def get_logs_json(limit: Optional[int] = 200, level: Optional[str] = None):
    """Returns log entries as JSON array."""
    logs = list(LOG_BUFFER)
    if level and level.upper() != "ALL":
        logs = [entry for entry in logs if entry["level"] == level.upper()]
    return JSONResponse(content=logs[:limit])


@router.delete("/api/logs")
async def clear_logs():
    """Clears all stored logs."""
    LOG_BUFFER.clear()
    add_custom_log("INFO", "system", "Logs cleared by user.")
    return JSONResponse(content={"status": "cleared"})


@router.get("/logs", response_class=HTMLResponse)
async def get_logs_page():
    """Renders a modern, responsive HTML dashboard to view backend logs in real time."""
    html_content = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Anilili Shorts Backend - Live Logs</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=Fira+Code:wght@400;500&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg-color: #0f111a;
            --card-bg: #181b29;
            --card-border: #282c40;
            --text-primary: #e2e8f0;
            --text-secondary: #94a3b8;
            --accent-purple: #8b5cf6;
            --accent-blue: #3b82f6;
            --success-green: #10b981;
            --warning-yellow: #f59e0b;
            --error-red: #ef4444;
            --font-main: 'Inter', sans-serif;
            --font-code: 'Fira Code', monospace;
        }

        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
        }

        body {
            background-color: var(--bg-color);
            color: var(--text-primary);
            font-family: var(--font-main);
            padding: 24px;
            min-height: 100vh;
        }

        header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding-bottom: 20px;
            border-bottom: 1px solid var(--card-border);
            margin-bottom: 24px;
            flex-wrap: wrap;
            gap: 16px;
        }

        .title-area {
            display: flex;
            align-items: center;
            gap: 12px;
        }

        .title-area h1 {
            font-size: 1.5rem;
            font-weight: 800;
            background: linear-gradient(135deg, #a78bfa, #60a5fa);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
        }

        .badge {
            background: rgba(139, 92, 246, 0.15);
            color: #a78bfa;
            border: 1px solid rgba(139, 92, 246, 0.3);
            padding: 4px 10px;
            border-radius: 20px;
            font-size: 0.75rem;
            font-weight: 600;
        }

        .controls {
            display: flex;
            align-items: center;
            gap: 12px;
            flex-wrap: wrap;
        }

        input[type="text"] {
            background: var(--card-bg);
            border: 1px solid var(--card-border);
            color: var(--text-primary);
            padding: 8px 14px;
            border-radius: 8px;
            font-size: 0.875rem;
            outline: none;
            width: 240px;
            transition: border-color 0.2s;
        }

        input[type="text"]:focus {
            border-color: var(--accent-purple);
        }

        .btn {
            background: var(--card-bg);
            border: 1px solid var(--card-border);
            color: var(--text-primary);
            padding: 8px 16px;
            border-radius: 8px;
            font-size: 0.875rem;
            font-weight: 600;
            cursor: pointer;
            display: inline-flex;
            align-items: center;
            gap: 6px;
            transition: all 0.2s;
        }

        .btn:hover {
            background: #23273b;
            border-color: #3b415e;
        }

        .btn-primary {
            background: var(--accent-purple);
            border-color: var(--accent-purple);
            color: white;
        }

        .btn-primary:hover {
            background: #7c3aed;
        }

        .btn-danger {
            background: rgba(239, 68, 68, 0.1);
            color: var(--error-red);
            border-color: rgba(239, 68, 68, 0.3);
        }

        .btn-danger:hover {
            background: rgba(239, 68, 68, 0.25);
        }

        .filter-chips {
            display: flex;
            gap: 8px;
        }

        .chip {
            padding: 6px 14px;
            border-radius: 20px;
            font-size: 0.8rem;
            font-weight: 600;
            cursor: pointer;
            background: var(--card-bg);
            border: 1px solid var(--card-border);
            color: var(--text-secondary);
            transition: all 0.2s;
        }

        .chip.active {
            background: var(--accent-blue);
            color: white;
            border-color: var(--accent-blue);
        }

        .stats-bar {
            display: flex;
            gap: 16px;
            margin-bottom: 16px;
            font-size: 0.85rem;
            color: var(--text-secondary);
        }

        .log-container {
            background: var(--card-bg);
            border: 1px solid var(--card-border);
            border-radius: 12px;
            overflow: hidden;
            box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.5);
        }

        table {
            width: 100%;
            border-collapse: collapse;
            text-align: left;
        }

        th {
            background: #131522;
            padding: 12px 16px;
            font-size: 0.75rem;
            text-transform: uppercase;
            letter-spacing: 0.05em;
            color: var(--text-secondary);
            border-bottom: 1px solid var(--card-border);
        }

        td {
            padding: 10px 16px;
            font-size: 0.85rem;
            border-bottom: 1px solid rgba(40, 44, 64, 0.6);
            vertical-align: top;
        }

        tr:hover {
            background: rgba(255, 255, 255, 0.02);
        }

        .time-col {
            font-family: var(--font-code);
            color: var(--text-secondary);
            white-space: nowrap;
            width: 170px;
        }

        .level-col {
            width: 100px;
        }

        .level-tag {
            display: inline-block;
            padding: 2px 8px;
            border-radius: 4px;
            font-size: 0.7rem;
            font-weight: 700;
            font-family: var(--font-code);
        }

        .level-INFO { background: rgba(16, 185, 129, 0.15); color: var(--success-green); }
        .level-WARNING { background: rgba(245, 158, 11, 0.15); color: var(--warning-yellow); }
        .level-ERROR { background: rgba(239, 68, 68, 0.2); color: var(--error-red); }

        .source-col {
            font-family: var(--font-code);
            color: #a78bfa;
            width: 140px;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }

        .msg-col {
            font-family: var(--font-code);
            word-break: break-word;
            line-height: 1.4;
            color: var(--text-primary);
        }

        .empty-state {
            padding: 40px;
            text-align: center;
            color: var(--text-secondary);
        }
    </style>
</head>
<body>
    <header>
        <div class="title-area">
            <h1>Anilili Shorts API</h1>
            <span class="badge">Live Logs</span>
        </div>
        <div class="controls">
            <input type="text" id="searchInput" placeholder="Search logs..." oninput="filterLogs()">
            <div class="filter-chips">
                <div class="chip active" onclick="setFilter('ALL', this)">ALL</div>
                <div class="chip" onclick="setFilter('INFO', this)">INFO</div>
                <div class="chip" onclick="setFilter('WARNING', this)">WARN</div>
                <div class="chip" onclick="setFilter('ERROR', this)">ERROR</div>
            </div>
            <button class="btn btn-primary" id="autoBtn" onclick="toggleAutoRefresh()">Auto Refresh: ON</button>
            <button class="btn btn-danger" onclick="clearLogs()">Clear</button>
        </div>
    </header>

    <div class="stats-bar">
        <span>Total Logs: <strong id="logCount">0</strong></span>
        <span>Auto-refresh: <strong id="refreshStatus" style="color: var(--success-green);">Active (2s)</strong></span>
    </div>

    <div class="log-container">
        <table>
            <thead>
                <tr>
                    <th class="time-col">Timestamp</th>
                    <th class="level-col">Level</th>
                    <th class="source-col">Source</th>
                    <th class="msg-col">Message / Details</th>
                </tr>
            </thead>
            <tbody id="logsBody">
                <tr><td colspan="4" class="empty-state">Loading logs...</td></tr>
            </tbody>
        </table>
    </div>

    <script>
        let allLogs = [];
        let currentFilter = 'ALL';
        let autoRefresh = true;
        let timer = null;

        async function fetchLogs() {
            try {
                const res = await fetch('/api/logs?limit=500');
                if (res.ok) {
                    allLogs = await res.json();
                    renderLogs();
                }
            } catch (e) {
                console.error("Failed to fetch logs:", e);
            }
        }

        function setFilter(level, element) {
            currentFilter = level;
            document.querySelectorAll('.chip').forEach(c => c.classList.remove('active'));
            element.classList.add('active');
            renderLogs();
        }

        function filterLogs() {
            renderLogs();
        }

        function renderLogs() {
            const query = document.getElementById('searchInput').value.toLowerCase().trim();
            const body = document.getElementById('logsBody');
            
            let filtered = allLogs.filter(log => {
                const matchLevel = currentFilter === 'ALL' || log.level === currentFilter;
                const matchQuery = !query || 
                    log.message.toLowerCase().includes(query) || 
                    log.source.toLowerCase().includes(query) ||
                    log.timestamp.includes(query);
                return matchLevel && matchQuery;
            });

            document.getElementById('logCount').innerText = filtered.length;

            if (filtered.length === 0) {
                body.innerHTML = `<tr><td colspan="4" class="empty-state">No logs found.</td></tr>`;
                return;
            }

            body.innerHTML = filtered.map(log => `
                <tr>
                    <td class="time-col">${escapeHtml(log.timestamp)}</td>
                    <td class="level-col"><span class="level-tag level-${log.level}">${log.level}</span></td>
                    <td class="source-col" title="${escapeHtml(log.source)}">${escapeHtml(log.source)}</td>
                    <td class="msg-col">${escapeHtml(log.message)}</td>
                </tr>
            `).join('');
        }

        function escapeHtml(str) {
            return (str || '').replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
        }

        async function clearLogs() {
            if (confirm("Clear all logs?")) {
                await fetch('/api/logs', { method: 'DELETE' });
                fetchLogs();
            }
        }

        function toggleAutoRefresh() {
            autoRefresh = !autoRefresh;
            const btn = document.getElementById('autoBtn');
            const status = document.getElementById('refreshStatus');
            if (autoRefresh) {
                btn.innerText = "Auto Refresh: ON";
                btn.className = "btn btn-primary";
                status.innerText = "Active (2s)";
                status.style.color = "var(--success-green)";
                startTimer();
            } else {
                btn.innerText = "Auto Refresh: OFF";
                btn.className = "btn";
                status.innerText = "Paused";
                status.style.color = "var(--text-secondary)";
                clearInterval(timer);
            }
        }

        function startTimer() {
            clearInterval(timer);
            timer = setInterval(fetchLogs, 2000);
        }

        fetchLogs();
        startTimer();
    </script>
</body>
</html>"""
    return HTMLResponse(content=html_content)
