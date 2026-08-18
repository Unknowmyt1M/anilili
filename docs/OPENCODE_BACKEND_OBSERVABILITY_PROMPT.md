# OpenCode Task — Complete Backend A→Z Debug Logging / Observability

## Mission

Work directly on the current `anilili` repository and implement a **production-quality backend execution-trace/logging system** so that when any backend operation fails, especially AnimeXIn scraping, provider resolution, playback URL extraction, metadata extraction, or `yt-dlp` processing, we can see **exactly what happened from the first step to the final failure/success**.

This is an implementation task, not a request for a plan. **Inspect the entire repository first, understand the current architecture and existing logging/API/storage implementation, then implement the solution in the architecture that best fits the codebase. Do not blindly follow a predefined implementation.**

The frontend does NOT need to be modified in this task. The backend must expose enough detailed information so a Logs/Debug UI can be added later without redesigning the backend logging system.

---

## Current Context

The app is currently in **debug/development mode**, so detailed backend logs are intentionally required. The purpose is to diagnose real failures such as:

- AnimeXIn provider failing while the UI only shows a generic failure.
- Anime/episode scraping succeeding partially but failing at a later provider step.
- `yt-dlp` extraction failing or returning incomplete formats.
- Playable URL extraction/refresh failing.
- Shorts discovery/feed replenishment failures.
- Metadata extraction failures.
- HTTP/network failures, retries, parser failures, timeouts, subprocess failures, and unexpected exceptions.

The current problem is that the application can show a failure, but the available logs do not reveal the complete execution path. Fix that at the backend level.

---

# 1. First: Fully Inspect the Existing Repository

Before changing anything:

1. Inspect the complete repository structure.
2. Identify the backend entry point(s).
3. Identify the existing logger implementation.
4. Identify existing `/api/app-logs` or equivalent logging endpoints.
5. Identify current log storage/in-memory mechanisms.
6. Identify all scraping/provider services.
7. Identify AnimeXIn scraping code.
8. Identify `yt-dlp` invocation and extraction code.
9. Identify all metadata-fetching code.
10. Identify video/playable URL extraction and refresh code.
11. Identify Playwright/browser automation, if present.
12. Identify Shorts discovery/feed services.
13. Identify retry/fallback mechanisms.
14. Identify all relevant dependencies already present.
15. Inspect tests and deployment configuration.

**Do not replace working infrastructure unnecessarily. Reuse compatible existing components where appropriate, but redesign weak logging/trace plumbing if necessary.**

Do not introduce a completely unrelated logging framework merely because it is convenient. Prefer the existing stack unless the existing implementation genuinely cannot support the required behavior.

---

# 2. Core Requirement: A→Z Execution Trace

Every important backend operation must produce a complete execution trace.

A log should not merely say:

> AnimeXIn failed

It should allow us to determine something like:

```text
Operation started
→ request received
→ target URL normalized
→ HTTP request started
→ response received
→ status = 200
→ HTML parsed
→ selector X attempted
→ selector X matched
→ title extracted
→ poster extracted
→ description extracted
→ episode list located
→ episode 1 URL extracted
→ provider detected
→ provider page requested
→ embed detected
→ player initialized
→ stream extraction started
→ candidate streams found
→ playable URL validation started
→ validation failed
→ fallback provider attempted
→ fallback failed
→ final error returned
```

The exact steps must come from the actual implementation. **Do not invent fake logs. Every log must correspond to a real operation/event.**

---

# 3. No Artificial Log Categorization

Do NOT create an overly restrictive category system that hides useful information.

The requirement is **full chronological execution visibility**.

You may use structured fields internally (operation ID, timestamp, service, etc.) for filtering and correlation, but the raw chronological event stream must remain complete.

A developer should be able to follow one operation from beginning to end without needing to guess which category contains the missing event.

---

# 4. Operation / Trace IDs

Introduce a correlation mechanism if one does not already exist.

Each meaningful backend request/operation should have a unique `operation_id` / `trace_id`.

Examples:

- anime metadata request
- episode scraping request
- provider resolution
- playable URL extraction
- `yt-dlp` extraction
- Shorts feed request
- Shorts discovery job
- playback URL refresh

Nested operations should retain the parent operation ID or have a clear parent-child relationship.

This is essential because multiple requests/jobs can run concurrently.

---

# 5. Log Every Real Backend Step

For each operation, capture as much of the following as is actually available:

- timestamp
- operation/trace ID
- parent operation ID when applicable
- request ID when applicable
- HTTP method
- endpoint/path
- source/client when known
- target URL/domain
- operation start/end
- elapsed time/duration
- step name/message
- relevant input parameters
- relevant output/result
- HTTP status
- response size when available
- retry number
- timeout information
- selected provider
- fallback provider
- parser/selector attempted
- extracted IDs
- extracted metadata
- number of results found
- number of candidates found
- validation result
- subprocess status
- subprocess exit code
- stdout/stderr where applicable
- exception type
- exception message
- complete traceback for unexpected exceptions

Do not log meaningless noise solely for the sake of volume. The requirement is **complete execution visibility**, not random spam.

---

# 6. AnimeXIn Scraping — Extremely Detailed Logging

AnimeXIn is one of the main failure points and must be fully traceable.

Instrument every actual stage, including where applicable:

### Request

- incoming API request
- target AnimeXIn URL
- URL normalization
- HTTP client initialization
- request start
- request completion
- status code
- response headers relevant to debugging
- response size
- timeout/retry

### Page Parsing

- HTML parsing start/end
- parser used
- selectors attempted
- selectors matched/not matched
- number of elements found
- title extraction
- poster extraction
- description extraction
- anime ID/slug extraction
- episode list extraction
- episode count
- individual episode URL extraction

### Provider Resolution

For every provider actually encountered:

- provider detected
- provider URL
- provider request started/completed
- response status
- parser stage
- embed URL discovery
- video ID extraction
- player/API endpoint discovery
- token/signature extraction if applicable
- candidate stream discovery
- playable URL extraction
- playable URL validation
- final provider result

If AnimeXIn fails, the log must identify **the exact stage and reason**.

Do not simply catch the exception and log `AnimeXIn failed`.

---

# 7. yt-dlp — FULL DEBUG TRACE

`yt-dlp` is a critical component. Instrument its real execution thoroughly.

Whenever `yt-dlp` is used, capture:

- extraction start
- target URL
- extractor selection/detection
- command/options actually used
- process start
- process PID if available
- stdout
- stderr
- warnings
- extractor messages
- network/request failures
- retry attempts
- format discovery
- number of formats discovered
- every available format returned by the current implementation
- format IDs
- extensions
- codecs
- resolution
- FPS
- bitrate when available
- audio/video presence
- protocol
- selected/default format
- playable URL extraction
- URL expiry information when available
- final extraction result
- process exit code
- total duration
- exception + traceback on failure

### Important

Do not create a second incompatible `yt-dlp` implementation merely for logging.

**Use the exact existing `yt-dlp` dependency/version and existing extraction mechanism already present in this repository.**

Preserve all currently supported formats and extraction behavior.

If the current code already extracts all available playable quality URLs, preserve that behavior and make the logs expose the complete result.

Do not silently downgrade quality or replace the existing extraction stack with another library.

---

# 8. Secrets and Sensitive Values

Detailed debugging is required, but never expose credentials/secrets merely because debug mode is enabled.

Never log raw values for:

- API keys
- OAuth access/refresh tokens
- cookies
- authorization headers
- session secrets
- database passwords
- private credentials

If the current operation requires these values, log that the value was present and redact/mask the actual secret.

For URLs, preserve useful debugging information but redact embedded credentials/tokens where necessary.

The goal is **maximum diagnostic detail without credential leakage**.

---

# 9. HTTP / Network / Retry Visibility

Whenever backend code performs network operations, expose enough detail to determine:

- which host was contacted
- which endpoint/path was requested
- when the request started
- how long it took
- status code
- response size
- timeout
- retry number
- retry reason
- final failure

For failed requests, include the actual exception type/message and traceback when it is an unexpected backend exception.

Do not log full response bodies by default when they may be huge. Where a response body is essential for debugging, use a bounded/truncated representation or explicit debug-only capture mechanism.

---

# 10. Browser / Playwright Logging

If Playwright or another browser automation layer exists, instrument the actual browser flow.

Capture applicable events such as:

- browser launch
- context creation
- page creation
- navigation start/end
- response status
- relevant requests
- relevant request failures
- console errors/warnings
- page errors
- selector waits
- selector success/failure
- clicks/actions actually performed
- timeout
- extracted values
- browser close

Do not fabricate browser events. Only record real events.

---

# 11. Shorts Backend Logging

The Shorts system must also be fully observable.

Trace:

- feed request
- cursor received
- requested limit
- DB query start/end
- number of rows returned
- ranking start/end
- next cursor generation
- feed exhaustion detection
- replenishment trigger
- discovery job acquisition/rejection
- Redis lock acquisition/release if present
- discovery source selection
- ytfetcher request/result
- API fallback request/result
- discovered item count
- duplicate count
- inserted count
- skipped count
- backdating/cursor handling if used
- final page construction
- response returned

For Shorts discovery, expose enough information to diagnose why an apparently infinite feed stops.

Do not remove existing feed/prefetch functionality while adding observability.

---

# 12. Metadata Extraction

For all metadata extraction paths, log:

- source selected
- request started/completed
- metadata parser started/completed
- fields attempted
- fields successfully extracted
- missing fields
- fallback source
- final metadata object/result
- failure reason

Preserve the current dependency/library used by the repository for metadata extraction.

Do not replace it with a different dependency unless the existing implementation is genuinely broken and replacement is necessary.

---

# 13. Playback / Playable URL Extraction

Trace the complete chain from episode/video selection to final playable URL.

For example:

```text
Episode selected
→ provider selected
→ provider request
→ embed discovered
→ stream extraction
→ formats discovered
→ quality candidates
→ playable URL candidates
→ validation
→ selected URL
→ player response
```

If the playable URL fails, the logs must show whether the failure happened during:

- provider discovery
- extraction
- URL parsing
- URL validation
- expiration
- network access
- player initialization
- fallback

Do not expose authentication tokens or secret query parameters.

---

# 14. Existing `/api/app-logs` Infrastructure

Inspect the current app-log implementation first.

If `/api/app-logs`, `/api/app-logs/devices`, `/app_logs`, or equivalent functionality already exists, **extend and improve it instead of creating an unrelated parallel logging system**, unless the existing design fundamentally prevents proper tracing.

Ensure the backend can provide:

- chronological logs
- operation/trace correlation
- complete message/details
- timestamps
- errors and tracebacks
- live/recent logs
- enough data for future frontend expansion

Maintain backward compatibility with existing consumers where practical.

---

# 15. Live Log Availability

The current app already polls the backend logs endpoint.

Preserve compatibility with that mechanism if possible.

New backend logs should become available to the existing log retrieval API without requiring frontend changes in this task.

If the current implementation is polling every few seconds, do not introduce a requirement for WebSockets/SSE just to complete this task. A future frontend can adopt streaming later if useful.

---

# 16. Error Handling

Every meaningful failure must have:

1. the operation ID
2. the exact step where it failed
3. the exception type
4. the exception message
5. traceback for unexpected exceptions
6. relevant context
7. retry/fallback information
8. final outcome

Do not swallow exceptions silently.

Do not convert every exception into a generic message before logging the original exception.

The client-facing error can remain clean; the backend diagnostic trace must remain detailed.

---

# 17. Performance Requirements

Detailed logging must not make the application unusably slow.

Use efficient logging and avoid expensive serialization/copying unless needed.

Do not synchronously perform expensive database operations for every tiny log event if the existing architecture allows a better approach.

However, **do not sacrifice the A→Z trace requirement merely to optimize prematurely**.

Keep enough recent history for debugging while respecting the repository's current storage model and deployment constraints.

---

# 18. Concurrency

The backend can handle multiple users/jobs simultaneously.

Logs from concurrent operations must remain distinguishable.

Never rely solely on timestamps to correlate logs.

Use operation/trace IDs.

Ensure background jobs and async tasks retain their operation context.

---

# 19. Do NOT Modify the Frontend in This Task

This task is intentionally backend-first.

Do NOT redesign the current frontend Logs page.

Do NOT add new frontend log UI unless a tiny backend-contract compatibility change is absolutely unavoidable.

The future frontend should be able to consume the backend logs and show expandable details, but that UI work will happen later.

---

# 20. Testing Requirements

After implementation, actually test the logging system.

At minimum test:

### Successful AnimeXIn flow

Verify that the complete successful trace is visible.

### AnimeXIn failure

Force or reproduce a provider failure and verify the exact failing stage is logged.

### yt-dlp success

Verify extractor/format/playable URL information is logged.

### yt-dlp failure

Verify stderr, exit code, exception, retry information, and traceback are available.

### Metadata failure

Verify the exact extraction stage is visible.

### Playback URL failure

Verify the complete provider → extraction → validation chain is visible.

### Shorts feed/discovery

Verify feed exhaustion/replenishment/discovery events can be followed through a single operation trace.

### Concurrent operations

Trigger multiple operations and verify their logs can be separated by operation ID.

---

# 21. Do Not Fake a Successful Test

Do not merely inspect the code and claim success.

Run the relevant tests/commands available in the repository.

If an external provider cannot be reached from the current environment, clearly state that in the final implementation summary and test everything that can be tested locally.

---

# 22. Preserve Existing Application Behavior

This is an observability/debugging task.

Do NOT intentionally change:

- existing API contracts
- provider priority
- metadata semantics
- playable URL semantics
- Shorts ranking behavior
- Shorts prefetch behavior
- existing quality selection behavior
- existing dependency choices

unless a change is strictly required to make logging correct.

If you discover an unrelated bug while implementing this, log/document it rather than silently redesigning unrelated functionality.

---

# 23. Quality Bar

The implementation should make this possible:

> A user reports "Episode playback failed."
>
> A developer opens backend logs, finds the operation ID, expands/follows the trace, and can determine exactly which request/parser/provider/extractor/validation step failed without reproducing the issue blindly.

That is the acceptance criterion.

---

# 24. Final Verification Checklist

Before finishing, verify:

- [ ] Repository architecture was inspected before implementation.
- [ ] Existing logging infrastructure was understood and reused where appropriate.
- [ ] Backend operations have correlation/operation IDs.
- [ ] AnimeXIn scraping has A→Z execution tracing.
- [ ] Provider resolution has A→Z execution tracing.
- [ ] Playable URL extraction has A→Z execution tracing.
- [ ] `yt-dlp` execution is deeply logged.
- [ ] Existing `yt-dlp` dependency/version is preserved.
- [ ] Existing metadata-fetch dependency is preserved.
- [ ] Available quality/playable URL extraction behavior is preserved.
- [ ] Network requests/retries/timeouts are visible.
- [ ] Browser automation is visible where applicable.
- [ ] Shorts feed/discovery operations are traceable.
- [ ] Exceptions retain type/message/traceback.
- [ ] Secrets are redacted.
- [ ] Existing `/api/app-logs` compatibility is preserved where possible.
- [ ] No frontend redesign was performed.
- [ ] Relevant tests were actually executed.
- [ ] No fake/synthetic success logs were added.
- [ ] The final response clearly lists changed files, tests run, and any limitations.

---

# Final Instruction to OpenCode

**Do the implementation yourself.**

Do not stop at analysis, do not return only a proposed architecture, and do not ask me to manually implement individual pieces.

Inspect the current codebase, determine the best backend architecture for this repository, implement the complete observability system, integrate it with the existing backend log APIs, run the available tests, and leave the repository in a working state.

The primary goal is simple:

**When anything fails in the backend, we must be able to see exactly what happened, step by step, from start to finish.**
