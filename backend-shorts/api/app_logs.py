"""
Anilili App Logs — Per-device Android app log ingestion and viewer.
POST /api/app-logs      → Android app sends batched logs
GET  /api/app-logs      → JSON log query (by device_id, level, limit)
GET  /api/app-logs/devices → List of known device IDs
DELETE /api/app-logs    → Clear logs for a device (or all)
GET  /app_logs          → Beautiful HTML dashboard with per-user filtering
"""

import datetime
import aiosqlite
from typing import List, Optional
from fastapi import APIRouter, Depends, Query
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel
from db.database import get_db

router = APIRouter(tags=["App Logs"])


# ─── Pydantic Models ────────────────────────────────────────────────────────

class AppLogEntry(BaseModel):
    level: str          # DEBUG, INFO, WARNING, ERROR, VERBOSE
    tag: str            # Android log tag e.g. "ShortsPrefetch"
    message: str
    timestamp: Optional[str] = None  # ISO timestamp from device, optional


class AppLogBatch(BaseModel):
    deviceId: str
    appVersion: Optional[str] = "unknown"
    logs: List[AppLogEntry]


# ─── API Endpoints ───────────────────────────────────────────────────────────

@router.post("/api/app-logs", status_code=201)
async def ingest_app_logs(
    batch: AppLogBatch,
    db: aiosqlite.Connection = Depends(get_db),
):
    """
    Android app posts batched logs here.
    Each batch identifies a device and app version, with a list of log entries.
    Logs are stored in SQLite app_logs table.
    Max 500 logs per request to prevent abuse.
    """
    now_iso = datetime.datetime.now(datetime.timezone.utc).isoformat()
    entries = batch.logs[:500]  # Clamp to 500 per batch

    rows = [
        (
            batch.deviceId,
            batch.appVersion or "unknown",
            (entry.level or "DEBUG").upper()[:10],
            (entry.tag or "app")[:64],
            (entry.message or "")[:4096],
            entry.timestamp or now_iso,
        )
        for entry in entries
    ]

    await db.executemany(
        """
        INSERT INTO app_logs (device_id, app_version, level, tag, message, received_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        rows,
    )
    await db.commit()
    return {"status": "ok", "inserted": len(rows)}


@router.get("/api/app-logs")
async def query_app_logs(
    device_id: Optional[str] = Query(None, description="Filter by device ID"),
    level: Optional[str] = Query(None, description="Filter by level: DEBUG, INFO, WARNING, ERROR"),
    tag: Optional[str] = Query(None, description="Filter by tag substring"),
    search: Optional[str] = Query(None, description="Search in message text"),
    limit: int = Query(200, ge=1, le=2000),
    db: aiosqlite.Connection = Depends(get_db),
):
    """Returns app logs as JSON with optional filters."""
    conditions = []
    params = []

    if device_id:
        conditions.append("device_id = ?")
        params.append(device_id)
    if level and level.upper() != "ALL":
        conditions.append("level = ?")
        params.append(level.upper())
    if tag:
        conditions.append("tag LIKE ?")
        params.append(f"%{tag}%")
    if search:
        conditions.append("message LIKE ?")
        params.append(f"%{search}%")

    where_clause = ("WHERE " + " AND ".join(conditions)) if conditions else ""
    params.append(limit)

    async with db.execute(
        f"""
        SELECT id, device_id, app_version, level, tag, message, received_at
        FROM app_logs
        {where_clause}
        ORDER BY id DESC
        LIMIT ?
        """,
        params,
    ) as cursor:
        rows = await cursor.fetchall()

    return JSONResponse(content=[
        {
            "id": row["id"],
            "deviceId": row["device_id"],
            "appVersion": row["app_version"],
            "level": row["level"],
            "tag": row["tag"],
            "message": row["message"],
            "receivedAt": row["received_at"],
        }
        for row in rows
    ])


@router.get("/api/app-logs/devices")
async def list_devices(db: aiosqlite.Connection = Depends(get_db)):
    """Returns list of distinct device IDs with their log counts and last seen time."""
    async with db.execute(
        """
        SELECT device_id, app_version, COUNT(*) as log_count, MAX(received_at) as last_seen
        FROM app_logs
        GROUP BY device_id
        ORDER BY last_seen DESC
        """
    ) as cursor:
        rows = await cursor.fetchall()

    return JSONResponse(content=[
        {
            "deviceId": row["device_id"],
            "appVersion": row["app_version"],
            "logCount": row["log_count"],
            "lastSeen": row["last_seen"],
        }
        for row in rows
    ])


@router.delete("/api/app-logs")
async def clear_app_logs(
    device_id: Optional[str] = Query(None, description="Clear logs for specific device only"),
    db: aiosqlite.Connection = Depends(get_db),
):
    """Clears app logs — for a specific device or all devices."""
    if device_id:
        await db.execute("DELETE FROM app_logs WHERE device_id = ?", (device_id,))
        msg = f"Cleared logs for device {device_id}"
    else:
        await db.execute("DELETE FROM app_logs")
        msg = "Cleared all app logs"
    await db.commit()
    return {"status": "ok", "message": msg}


# ─── HTML Dashboard ──────────────────────────────────────────────────────────

@router.get("/app_logs", response_class=HTMLResponse)
async def app_logs_page():
    """
    Premium HTML dashboard for viewing per-device Android app logs.
    Shows logs per user/device with live filtering, search, and level badges.
    """
    html = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Anilili — App Logs</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=Fira+Code:wght@400;500&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg: #080a12;
            --surface: #10131f;
            --surface2: #161927;
            --border: #1e2235;
            --border2: #252a40;
            --text: #e2e8f0;
            --muted: #64748b;
            --accent: #7c3aed;
            --accent-light: #a78bfa;
            --blue: #3b82f6;
            --green: #10b981;
            --yellow: #f59e0b;
            --red: #ef4444;
            --orange: #f97316;
            --cyan: #06b6d4;
            --mono: 'Fira Code', monospace;
            --sans: 'Inter', sans-serif;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        html, body { height: 100%; }
        body {
            background: var(--bg);
            color: var(--text);
            font-family: var(--sans);
            display: flex;
            flex-direction: column;
            height: 100vh;
            overflow: hidden;
        }

        /* ── Top Bar ── */
        .topbar {
            display: flex;
            align-items: center;
            gap: 16px;
            padding: 14px 20px;
            background: var(--surface);
            border-bottom: 1px solid var(--border);
            flex-shrink: 0;
            flex-wrap: wrap;
        }
        .logo {
            font-size: 1.1rem;
            font-weight: 800;
            background: linear-gradient(135deg, #a78bfa, #60a5fa);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            white-space: nowrap;
        }
        .badge-pill {
            background: rgba(124,58,237,0.15);
            color: var(--accent-light);
            border: 1px solid rgba(124,58,237,0.3);
            padding: 3px 10px;
            border-radius: 20px;
            font-size: 0.72rem;
            font-weight: 600;
        }
        .topbar-right {
            display: flex;
            align-items: center;
            gap: 10px;
            margin-left: auto;
            flex-wrap: wrap;
        }

        /* ── Inputs / Buttons ── */
        select, input[type="text"] {
            background: var(--surface2);
            border: 1px solid var(--border2);
            color: var(--text);
            padding: 7px 12px;
            border-radius: 8px;
            font-size: 0.82rem;
            font-family: var(--sans);
            outline: none;
            transition: border-color 0.2s;
        }
        select { cursor: pointer; min-width: 180px; }
        input[type="text"] { width: 220px; }
        select:focus, input[type="text"]:focus { border-color: var(--accent); }

        .btn {
            padding: 7px 14px;
            border-radius: 8px;
            font-size: 0.82rem;
            font-weight: 600;
            cursor: pointer;
            border: 1px solid var(--border2);
            background: var(--surface2);
            color: var(--text);
            transition: all 0.15s;
            display: inline-flex;
            align-items: center;
            gap: 6px;
            white-space: nowrap;
        }
        .btn:hover { background: #1e2235; border-color: #2e3555; }
        .btn-accent { background: var(--accent); border-color: var(--accent); color: #fff; }
        .btn-accent:hover { background: #6d28d9; }
        .btn-danger { background: rgba(239,68,68,0.1); color: var(--red); border-color: rgba(239,68,68,0.3); }
        .btn-danger:hover { background: rgba(239,68,68,0.2); }

        .level-chips { display: flex; gap: 6px; align-items: center; }
        .chip {
            padding: 5px 12px;
            border-radius: 20px;
            font-size: 0.75rem;
            font-weight: 600;
            cursor: pointer;
            border: 1px solid var(--border2);
            background: var(--surface2);
            color: var(--muted);
            transition: all 0.15s;
        }
        .chip:hover { color: var(--text); }
        .chip.active-ALL    { background: var(--blue); border-color: var(--blue); color: #fff; }
        .chip.active-DEBUG  { background: rgba(100,116,139,0.3); border-color: #64748b; color: #94a3b8; }
        .chip.active-VERBOSE{ background: rgba(6,182,212,0.15); border-color: var(--cyan); color: var(--cyan); }
        .chip.active-INFO   { background: rgba(16,185,129,0.15); border-color: var(--green); color: var(--green); }
        .chip.active-WARNING{ background: rgba(245,158,11,0.15); border-color: var(--yellow); color: var(--yellow); }
        .chip.active-ERROR  { background: rgba(239,68,68,0.15); border-color: var(--red); color: var(--red); }

        /* ── Layout ── */
        .main {
            display: flex;
            flex: 1;
            overflow: hidden;
        }

        /* ── Device Sidebar ── */
        .sidebar {
            width: 240px;
            flex-shrink: 0;
            border-right: 1px solid var(--border);
            display: flex;
            flex-direction: column;
            background: var(--surface);
            overflow: hidden;
        }
        .sidebar-header {
            padding: 12px 16px;
            font-size: 0.72rem;
            font-weight: 700;
            text-transform: uppercase;
            letter-spacing: 0.06em;
            color: var(--muted);
            border-bottom: 1px solid var(--border);
            flex-shrink: 0;
        }
        .device-list {
            flex: 1;
            overflow-y: auto;
            padding: 8px 0;
        }
        .device-item {
            padding: 10px 16px;
            cursor: pointer;
            transition: background 0.12s;
            border-left: 3px solid transparent;
        }
        .device-item:hover { background: rgba(255,255,255,0.03); }
        .device-item.selected {
            background: rgba(124,58,237,0.1);
            border-left-color: var(--accent);
        }
        .device-id {
            font-size: 0.8rem;
            font-family: var(--mono);
            color: var(--text);
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }
        .device-meta {
            font-size: 0.7rem;
            color: var(--muted);
            margin-top: 2px;
        }
        .device-count {
            display: inline-block;
            background: rgba(124,58,237,0.2);
            color: var(--accent-light);
            padding: 1px 7px;
            border-radius: 10px;
            font-size: 0.68rem;
            font-weight: 700;
            margin-left: 4px;
        }
        .all-devices-btn {
            padding: 10px 16px;
            font-size: 0.8rem;
            cursor: pointer;
            color: var(--accent-light);
            border-bottom: 1px solid var(--border);
            display: flex;
            align-items: center;
            gap: 6px;
            font-weight: 600;
            transition: background 0.12s;
        }
        .all-devices-btn:hover, .all-devices-btn.selected { background: rgba(124,58,237,0.08); }

        /* ── Log Panel ── */
        .log-panel {
            flex: 1;
            display: flex;
            flex-direction: column;
            overflow: hidden;
        }
        .log-toolbar {
            display: flex;
            align-items: center;
            gap: 10px;
            padding: 10px 16px;
            background: var(--surface);
            border-bottom: 1px solid var(--border);
            flex-shrink: 0;
            flex-wrap: wrap;
        }
        .log-stats {
            font-size: 0.78rem;
            color: var(--muted);
            margin-left: auto;
        }
        .log-stats strong { color: var(--text); }

        .log-table-wrap {
            flex: 1;
            overflow-y: auto;
            overflow-x: hidden;
        }
        table {
            width: 100%;
            border-collapse: collapse;
        }
        thead th {
            position: sticky;
            top: 0;
            background: #0d1020;
            padding: 9px 14px;
            font-size: 0.7rem;
            font-weight: 700;
            text-transform: uppercase;
            letter-spacing: 0.05em;
            color: var(--muted);
            border-bottom: 1px solid var(--border);
            white-space: nowrap;
            z-index: 10;
        }
        td {
            padding: 7px 14px;
            font-size: 0.8rem;
            border-bottom: 1px solid rgba(30,34,53,0.7);
            vertical-align: top;
        }
        tr:hover td { background: rgba(255,255,255,0.018); }
        .col-time { font-family: var(--mono); color: var(--muted); white-space: nowrap; width: 160px; }
        .col-level { width: 90px; }
        .col-tag { font-family: var(--mono); color: #a78bfa; width: 140px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 140px; }
        .col-msg { font-family: var(--mono); word-break: break-word; line-height: 1.5; color: var(--text); }
        .col-device { font-family: var(--mono); color: var(--cyan); width: 130px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-size: 0.72rem; }

        .level-badge {
            display: inline-block;
            padding: 2px 7px;
            border-radius: 4px;
            font-size: 0.68rem;
            font-weight: 700;
            font-family: var(--mono);
            white-space: nowrap;
        }
        .lv-DEBUG   { background: rgba(100,116,139,0.2); color: #94a3b8; }
        .lv-VERBOSE { background: rgba(6,182,212,0.12); color: var(--cyan); }
        .lv-INFO    { background: rgba(16,185,129,0.15); color: var(--green); }
        .lv-WARNING { background: rgba(245,158,11,0.15); color: var(--yellow); }
        .lv-ERROR   { background: rgba(239,68,68,0.18); color: var(--red); }
        .lv-ASSERT  { background: rgba(249,115,22,0.18); color: var(--orange); }

        .empty-state {
            text-align: center;
            padding: 60px 20px;
            color: var(--muted);
            font-size: 0.9rem;
        }
        .empty-state .icon { font-size: 2.5rem; margin-bottom: 12px; }

        /* Live indicator */
        .live-dot {
            width: 8px; height: 8px;
            border-radius: 50%;
            background: var(--green);
            display: inline-block;
            animation: pulse 1.8s infinite;
        }
        @keyframes pulse {
            0%, 100% { opacity: 1; }
            50% { opacity: 0.3; }
        }

        /* Scrollbar */
        ::-webkit-scrollbar { width: 6px; }
        ::-webkit-scrollbar-track { background: transparent; }
        ::-webkit-scrollbar-thumb { background: var(--border2); border-radius: 4px; }
        ::-webkit-scrollbar-thumb:hover { background: #2e3555; }
    </style>
</head>
<body>
    <!-- Top Bar -->
    <div class="topbar">
        <span class="logo">🎌 Anilili</span>
        <span class="badge-pill">App Logs</span>
        <span class="live-dot" title="Auto-refresh active"></span>

        <div class="topbar-right">
            <input type="text" id="searchInput" placeholder="🔍 Search message / tag..." oninput="applyFilters()">
            <div class="level-chips" id="levelChips">
                <div class="chip active-ALL" onclick="setLevel('ALL', this)">All</div>
                <div class="chip" onclick="setLevel('VERBOSE', this)">Verbose</div>
                <div class="chip" onclick="setLevel('DEBUG', this)">Debug</div>
                <div class="chip" onclick="setLevel('INFO', this)">Info</div>
                <div class="chip" onclick="setLevel('WARNING', this)">Warn</div>
                <div class="chip" onclick="setLevel('ERROR', this)">Error</div>
            </div>
            <button class="btn btn-accent" onclick="refreshAll()">↻ Refresh</button>
            <button class="btn btn-danger" onclick="clearDeviceLogs()">🗑 Clear</button>
        </div>
    </div>

    <div class="main">
        <!-- Device Sidebar -->
        <div class="sidebar">
            <div class="sidebar-header">Devices</div>
            <div class="all-devices-btn selected" id="allDevicesBtn" onclick="selectDevice(null, this)">
                📡 All Devices
            </div>
            <div class="device-list" id="deviceList">
                <div style="padding:16px;color:var(--muted);font-size:0.8rem;">Loading...</div>
            </div>
        </div>

        <!-- Log Panel -->
        <div class="log-panel">
            <div class="log-toolbar">
                <span id="deviceLabel" style="font-size:0.82rem;color:var(--muted);">Showing all devices</span>
                <div class="log-stats">
                    <strong id="logCount">0</strong> logs &nbsp;|&nbsp;
                    Auto-refresh: <strong style="color:var(--green);">ON (3s)</strong>
                </div>
            </div>

            <div class="log-table-wrap">
                <table>
                    <thead>
                        <tr>
                            <th class="col-time">Time</th>
                            <th class="col-level">Level</th>
                            <th class="col-device" id="colDeviceHeader">Device</th>
                            <th class="col-tag">Tag</th>
                            <th class="col-msg">Message</th>
                        </tr>
                    </thead>
                    <tbody id="logsBody">
                        <tr><td colspan="5" class="empty-state"><div class="icon">📡</div>Loading logs...</td></tr>
                    </tbody>
                </table>
            </div>
        </div>
    </div>

    <script>
        let allLogs = [];
        let devices = [];
        let selectedDevice = null;
        let currentLevel = 'ALL';
        let refreshTimer = null;

        // ── Fetch devices list ──────────────────────────────
        async function fetchDevices() {
            try {
                const res = await fetch('/api/app-logs/devices');
                if (!res.ok) return;
                devices = await res.json();
                renderDevices();
            } catch(e) {}
        }

        function renderDevices() {
            const list = document.getElementById('deviceList');
            if (devices.length === 0) {
                list.innerHTML = '<div style="padding:16px;color:var(--muted);font-size:0.78rem;">No devices yet.<br>App logs will appear here once the Android app connects.</div>';
                return;
            }
            list.innerHTML = devices.map(d => `
                <div class="device-item ${selectedDevice === d.deviceId ? 'selected' : ''}"
                     onclick="selectDevice('${esc(d.deviceId)}', this)"
                     title="${esc(d.deviceId)}">
                    <div class="device-id">${esc(truncate(d.deviceId, 22))}<span class="device-count">${d.logCount}</span></div>
                    <div class="device-meta">v${esc(d.appVersion)} · ${timeAgo(d.lastSeen)}</div>
                </div>
            `).join('');
        }

        function selectDevice(deviceId, el) {
            selectedDevice = deviceId;
            // Update sidebar selection
            document.querySelectorAll('.device-item').forEach(d => d.classList.remove('selected'));
            document.getElementById('allDevicesBtn').classList.remove('selected');
            if (el) el.classList.add('selected');

            const label = document.getElementById('deviceLabel');
            const colHeader = document.getElementById('colDeviceHeader');
            if (deviceId) {
                label.textContent = '📱 ' + deviceId;
                colHeader.style.display = 'table-cell';
            } else {
                label.textContent = 'Showing all devices';
                colHeader.style.display = 'table-cell';
            }
            fetchLogs();
        }

        // ── Fetch logs ──────────────────────────────────────
        async function fetchLogs() {
            try {
                let url = '/api/app-logs?limit=500';
                if (selectedDevice) url += '&device_id=' + encodeURIComponent(selectedDevice);
                if (currentLevel !== 'ALL') url += '&level=' + currentLevel;
                const res = await fetch(url);
                if (!res.ok) return;
                allLogs = await res.json();
                applyFilters();
            } catch(e) {}
        }

        // ── Filter & Render ─────────────────────────────────
        function applyFilters() {
            const query = document.getElementById('searchInput').value.toLowerCase().trim();
            let filtered = allLogs.filter(log => {
                if (!query) return true;
                return (log.message || '').toLowerCase().includes(query)
                    || (log.tag || '').toLowerCase().includes(query)
                    || (log.deviceId || '').toLowerCase().includes(query);
            });

            document.getElementById('logCount').textContent = filtered.length;
            const body = document.getElementById('logsBody');

            if (filtered.length === 0) {
                body.innerHTML = `<tr><td colspan="5" class="empty-state"><div class="icon">🔍</div>No logs match your filters.</td></tr>`;
                return;
            }

            body.innerHTML = filtered.map(log => `
                <tr>
                    <td class="col-time">${esc(formatTime(log.receivedAt))}</td>
                    <td class="col-level"><span class="level-badge lv-${log.level}">${log.level}</span></td>
                    <td class="col-device" title="${esc(log.deviceId)}">${esc(truncate(log.deviceId, 16))}</td>
                    <td class="col-tag" title="${esc(log.tag)}">${esc(log.tag)}</td>
                    <td class="col-msg">${esc(log.message)}</td>
                </tr>
            `).join('');
        }

        function setLevel(level, el) {
            currentLevel = level;
            document.querySelectorAll('.chip').forEach(c => {
                c.className = 'chip';
            });
            el.className = 'chip active-' + level;
            fetchLogs();
        }

        // ── Actions ─────────────────────────────────────────
        async function clearDeviceLogs() {
            const msg = selectedDevice
                ? `Clear all logs for device "${selectedDevice}"?`
                : 'Clear ALL app logs from ALL devices?';
            if (!confirm(msg)) return;
            let url = '/api/app-logs';
            if (selectedDevice) url += '?device_id=' + encodeURIComponent(selectedDevice);
            await fetch(url, { method: 'DELETE' });
            allLogs = [];
            applyFilters();
            fetchDevices();
        }

        async function refreshAll() {
            await fetchDevices();
            await fetchLogs();
        }

        // ── Auto-refresh ────────────────────────────────────
        function startAutoRefresh() {
            clearInterval(refreshTimer);
            refreshTimer = setInterval(refreshAll, 3000);
        }

        // ── Utils ───────────────────────────────────────────
        function esc(str) {
            return (str || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
        }

        function truncate(str, len) {
            return str && str.length > len ? '…' + str.slice(-len) : (str || '');
        }

        function formatTime(iso) {
            if (!iso) return '';
            try {
                const d = new Date(iso);
                return d.toLocaleTimeString('en-IN', { hour12: false }) + '.' + String(d.getMilliseconds()).padStart(3,'0');
            } catch { return iso; }
        }

        function timeAgo(iso) {
            if (!iso) return '';
            try {
                const diff = Math.floor((Date.now() - new Date(iso)) / 1000);
                if (diff < 60) return diff + 's ago';
                if (diff < 3600) return Math.floor(diff/60) + 'm ago';
                if (diff < 86400) return Math.floor(diff/3600) + 'h ago';
                return Math.floor(diff/86400) + 'd ago';
            } catch { return ''; }
        }

        // ── Init ────────────────────────────────────────────
        refreshAll();
        startAutoRefresh();
    </script>
</body>
</html>"""
    return HTMLResponse(content=html)
