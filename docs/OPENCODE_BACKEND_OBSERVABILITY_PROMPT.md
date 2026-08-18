# OpenCode Task — Complete Backend A→Z Debug Logging / Observability

## Mission

Work directly on the current `anilili` repository and implement a **production-quality backend execution-trace/logging system** for the current debug/development build.

This is an implementation task, not a request for a plan. **Inspect the entire repository first, understand the current architecture and existing logging/API/storage implementation, then implement the solution in the architecture that best fits the codebase. Do not blindly follow a predefined implementation.**

The goal is simple: when something fails anywhere in the backend, especially AnimeXIn scraping, provider resolution, metadata extraction, playable URL extraction, `yt-dlp`, Shorts discovery/feed replenishment, or playback URL refresh, we must be able to follow the complete real execution path from the first event to the final success/failure.

The app is currently in debug mode, so detailed A→Z diagnostic logging is intentionally required.

---

# 1. FIRST: FULL REPOSITORY INSPECTION

Before modifying code:

1. Inspect the complete repository structure.
2. Identify all backend entry points.
3. Identify the existing logger and logging helpers.
4. Identify existing `/api/app-logs`, `/api/app-logs/devices`, `/app_logs`, or equivalent endpoints.
5. Identify how server logs are currently produced/stored.
6. Identify how app/client logs are currently received/stored.
7. Identify whether **server logs and app logs currently use separate storage, endpoints, pages, or models**.
8. Identify AnimeXIn scraping/provider code.
9. Identify metadata-fetching code and its current dependencies.
10. Identify `yt-dlp` invocation and extraction code.
11. Identify playable URL extraction/refresh code.
12. Identify Playwright/browser automation if present.
13. Identify Shorts feed, ranking, discovery, prefetch, cursor, and replenishment code.
14. Identify retry/fallback logic.
15. Inspect tests, dependency files, environment configuration, and deployment configuration.

Do not replace working infrastructure unnecessarily. Reuse compatible existing components where appropriate, but redesign weak logging/trace plumbing if the current design cannot provide the required observability.

Do not introduce a completely unrelated logging framework merely for convenience. Prefer the existing stack unless it genuinely cannot satisfy these requirements.

---

# 2. CRITICAL REQUIREMENT — ONE UNIFIED LOG STREAM

## Server logs and App logs MUST be shown as ONE logical log stream

The current backend has **server logs** and **app logs** appearing separately. This makes debugging extremely difficult because a single user action can produce events in both systems and there is no reliable chronological view of the complete operation.

**Fix this at the backend/logging architecture level.**

The final backend must expose a **single unified chronological log stream** containing both:

- backend/server-generated logs
- application/client-generated logs received through the app-log API

They must be merged into the same logical history using a common event schema.

### Important

Do NOT simply create another page or tell the frontend to make two API calls and visually place two lists together.

The backend must provide a unified source of truth so a future/current Logs page can request one stream and see both types of events chronologically.

For example:

```text
10:00:01.100  SERVER  operation=abc  GET /episode/123 started
10:00:01.180  SERVER  operation=abc  AnimeXIn request started
10:00:01.450  SERVER  operation=abc  AnimeXIn response 200
10:00:01.500  SERVER  operation=abc  episode parser started
10:00:01.620  APP     operation=abc  player screen opened
10:00:01.800  SERVER  operation=abc  provider=AnimeXIn selected
10:00:02.100  SERVER  operation=abc  playable URL extraction started
10:00:02.300  APP     operation=abc  playback loading
10:00:03.000  SERVER  operation=abc  provider extraction failed
10:00:03.010  SERVER  operation=abc  fallback provider started
10:00:03.500  SERVER  operation=abc  final failure
```

The exact events must come from real execution. **Never fabricate logs.**

### Preserve source information

Even though the streams are unified, each event should retain a `source`/`origin` field such as:

- `server`
- `app`
- `system`
- `worker`
- another real source present in the repository

This is for diagnostics and future filtering only. It must NOT split the actual chronological stream.

---

# 3. ONE COMMON EVENT MODEL

Create or extend the existing log event model so server and app logs can coexist.

A useful event should support fields such as:

- timestamp
- source/origin
- level
- operation_id / trace_id
- parent_operation_id when applicable
- request_id when applicable
- device/session identifier when available and safe
- HTTP method
- endpoint/path
- service/component
- step/event name
- human-readable message
- structured details
- duration/elapsed time
- status code
- retry number
- exception type
- exception message
- traceback
- relevant result information

Do not force every event to populate every field. Store what is actually available.

The raw chronological event stream must remain complete even if filtering/grouping is added later.

---

# 4. OPERATION / TRACE IDs

Every meaningful backend operation must have a unique correlation ID.

Examples:

- anime metadata request
- anime detail scraping
- episode scraping
- provider resolution
- playable URL extraction
- playback URL refresh
- `yt-dlp` extraction
- Shorts feed request
- Shorts discovery
- feed replenishment
- background job

Nested operations must retain the parent operation ID or have a clear parent-child relationship.

When an app/client log belongs to an operation initiated by the backend, associate it with the same operation/trace ID whenever the current architecture allows it.

If the app cannot currently know the server's operation ID, preserve the app event and use request/session/device correlation where available rather than dropping it.

Do not rely on timestamps alone to correlate concurrent operations.

---

# 5. A→Z EXECUTION TRACE

Every important backend operation must produce a complete real execution trace.

A useful trace should make it possible to understand something like:

```text
request received
→ operation created
→ input validated
→ URL normalized
→ HTTP request started
→ response received
→ response status checked
→ HTML parsed
→ selectors attempted
→ metadata extracted
→ episode list found
→ provider detected
→ provider request started
→ embed discovered
→ stream extraction started
→ candidates found
→ playable URL validation
→ selected stream
→ response returned
```

The actual trace must reflect what the code really did.

Do not add fake progress messages such as `processing...` unless they correspond to a real operation.

---

# 6. LOG EVERY REAL BACKEND STEP

For applicable operations capture:

- timestamp
- source
- operation/trace ID
- parent operation ID
- request ID
- HTTP method/path
- client/device information when available
- target URL/domain
- operation start/end
- duration
- exact step/message
- relevant input
- relevant output/result
- status code
- response size
- retry number
- timeout information
- provider selected
- fallback provider
- parser/selector attempted
- IDs extracted
- metadata fields extracted
- candidate count
- validation result
- subprocess state
- subprocess exit code
- stdout/stderr where applicable
- exception type
- exception message
- complete traceback for unexpected exceptions

Do not create useless spam. The requirement is **complete execution visibility**, not random high-volume logging.

---

# 7. ANIMEXIN — EXTREMELY DETAILED TRACE

AnimeXIn is a major failure point. Instrument every real stage.

### Request stage

Log, where applicable:

- incoming request
- target AnimeXIn URL
- URL normalization
- HTTP client creation/configuration
- request start
- request completion
- status code
- relevant response headers
- response size
- timeout
- retry number/reason

### Parsing stage

Log:

- HTML parsing start/end
- parser used
- every meaningful selector/parser step
- selector matched/not matched
- number of elements found
- title extraction
- poster extraction
- description extraction
- anime ID/slug extraction
- episode list extraction
- episode count
- episode URL extraction

### Provider stage

For every provider actually encountered:

- provider detected
- provider URL
- provider request start/end
- response status
- parser stage
- embed discovery
- video ID extraction
- player/API endpoint discovery
- token/signature discovery if applicable
- candidate stream discovery
- playable URL extraction
- playable URL validation
- final provider result

If AnimeXIn fails, logs MUST identify the exact stage and actual reason.

Do not collapse everything into `AnimeXIn failed`.

---

# 8. yt-dlp — FULL REAL DEBUG TRACE

`yt-dlp` is critical. Instrument its actual execution without creating a second incompatible implementation.

Whenever `yt-dlp` is used, capture as applicable:

- extraction start
- target URL
- extractor detection/selection
- options actually used
- process start
- PID if available
- stdout
- stderr
- warnings
- extractor messages
- network failures
- retry attempts
- format discovery
- number of formats
- **all formats returned by the existing implementation**
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
- exit code
- duration
- exception + traceback on failure

### Mandatory dependency preservation

Use the **same existing `yt-dlp` dependency/version and extraction mechanism already present in the repository**.

Do not replace it merely to make logging easier.

Preserve the existing behavior for all available quality/playable URLs.

If the existing implementation already extracts multiple playable qualities, keep that behavior and expose the result through logs.

Do not silently downgrade quality.

---

# 9. METADATA FETCHING

Use the existing metadata-fetch dependency/library already used by the repository.

For every metadata operation log:

- source selected
- request start/end
- parser start/end
- fields attempted
- fields successfully extracted
- missing fields
- fallback source
- final result
- failure reason

Do not replace the current dependency unless it is genuinely broken and replacement is necessary.

---

# 10. PLAYABLE URL / STREAM EXTRACTION

Trace the complete chain:

```text
video/episode selected
→ provider selected
→ provider requested
→ embed discovered
→ extractor started
→ formats/candidates discovered
→ playable URLs extracted
→ URLs validated
→ quality candidates available
→ selected URL
→ player response
```

If extraction fails, logs must identify whether the failure happened during:

- provider discovery
- HTTP request
- parser
- embed discovery
- extractor
- URL parsing
- URL validation
- expiration
- network access
- player initialization
- fallback

Never expose authentication tokens or secret query parameters.

---

# 11. PLAYABLE QUALITY REQUIREMENT

Preserve the current implementation's available quality extraction behavior.

When the code already exposes multiple playable quality URLs, logging must show the real available candidates, including useful fields such as:

- quality/resolution
- format ID
- codec
- extension
- bitrate
- FPS
- protocol
- audio/video availability
- URL status/validation result

Do not invent quality levels that were not actually returned.

Do not replace the existing extraction pipeline just for observability.

---

# 12. HTTP / NETWORK / RETRIES

Every meaningful network operation should expose enough information to determine:

- host
- path/endpoint
- start time
- duration
- status code
- response size
- timeout
- retry number
- retry reason
- final failure

For unexpected failures include exception type/message and traceback.

Do not dump huge response bodies by default. If a body is essential for debugging, use bounded/truncated debug capture.

---

# 13. PLAYWRIGHT / BROWSER AUTOMATION

If Playwright or another browser automation layer exists, trace the real browser flow:

- browser launch
- context creation
- page creation
- navigation start/end
- relevant response status
- relevant request
- request failure
- console errors/warnings
- page errors
- selector wait
- selector success/failure
- actual clicks/actions
- timeout
- extracted values
- browser close

Never fabricate browser events.

---

# 14. SHORTS BACKEND — COMPLETE TRACE

The Shorts backend must remain fully observable.

Trace:

- feed request
- cursor received
- requested limit
- DB query start/end
- row count
- ranking start/end
- next cursor generation
- feed exhaustion detection
- replenishment trigger
- discovery lock acquisition/rejection
- discovery source selection
- ytfetcher request/result
- YouTube API fallback request/result
- discovered count
- duplicate count
- inserted count
- skipped count
- cursor/backdating handling if used
- page construction
- response returned

The logs must make it possible to diagnose why an apparently infinite Shorts feed stops.

Do not remove or break existing Shorts prefetch, ranking, discovery, or playback behavior while adding observability.

---

# 15. ERROR HANDLING

Every meaningful failure must retain:

1. operation ID
2. exact failing step
3. exception type
4. exception message
5. traceback for unexpected exceptions
6. relevant context
7. retry/fallback information
8. final outcome

Never silently swallow exceptions.

Never replace the original exception with only a generic client-facing message before recording the diagnostic event.

The API response can remain clean while backend logs remain extremely detailed.

---

# 16. APP LOG INGESTION

Inspect the existing app-log endpoint and client logging implementation.

If the app currently sends logs through `/api/app-logs`:

- preserve compatibility where practical
- normalize incoming events into the common event schema
- preserve their original timestamp when trustworthy
- record server receipt time as well when useful
- preserve source as `app`
- attach operation/request/session/device correlation when available
- retain full diagnostic details in debug mode

Do not silently discard app events merely because they do not have an operation ID.

---

# 17. UNIFIED `/api/app-logs` / LOG RETRIEVAL CONTRACT

The existing log retrieval mechanism should become the unified source for both server and app events.

If `/api/app-logs` is currently the existing log endpoint, **extend it so it returns the merged stream rather than only client/app events**.

If there is a better existing endpoint already used by the repository, adapt that endpoint instead, but avoid creating redundant parallel log systems.

The unified response should support, where compatible with the current API:

- chronological ordering
- newest/oldest pagination or bounded history
- source field
- level
- timestamp
- operation/trace ID
- message
- detailed structured data
- exception/traceback
- request information
- device/session information when available

Maintain backward compatibility with existing consumers wherever reasonably possible.

If changing the response shape is unavoidable, preserve old fields and add new fields rather than removing existing ones.

---

# 18. SERVER LOGS MUST NOT BE LOST

Current server logs such as:

```text
GET /api/app-logs -> 200
POST /shorts/.../react -> 200
GET /youtube/validate-key -> 401
GET /.well-known/... -> 404
```

are themselves useful diagnostic events.

The unified system must capture real backend HTTP request/response events where the current server architecture can observe them.

Expected harmless requests such as browser probes or favicon requests may still appear as real events. Do not hide them merely because they are noisy.

The goal is accurate visibility.

However, do not classify every 4xx/5xx as an application failure without considering the actual request context.

---

# 19. LIVE LOG AVAILABILITY

The current app already polls the backend logs endpoint.

Preserve compatibility with that mechanism if possible.

New server and app logs must become visible through the **same unified retrieval path**.

Do not require WebSockets/SSE just to complete this task.

A future UI may add streaming later.

---

# 20. NO ARTIFICIAL CATEGORY WALLS

Do not create a design where important events are hidden behind rigid categories such as `AnimeXIn`, `yt-dlp`, `network`, `app`, etc.

Structured fields are welcome for filtering, but the primary view/data source must remain a complete chronological stream.

A developer should be able to follow one operation from start to finish without manually jumping between unrelated log systems.

---

# 21. SECRETS / SENSITIVE DATA

Debug mode does NOT mean secrets may be logged.

Never log raw:

- API keys
- OAuth access/refresh tokens
- cookies
- authorization headers
- session secrets
- database passwords
- private credentials
- authentication tokens embedded in URLs

Log that the value existed when useful, but mask/redact the actual value.

Preserve non-sensitive URL information needed for debugging.

---

# 22. CONCURRENCY

Multiple requests/jobs may run concurrently.

Logs must remain distinguishable using operation/trace IDs.

Background tasks and async jobs must preserve their operation context.

Do not rely only on timestamps.

---

# 23. PERFORMANCE / STORAGE

Detailed logging must not make the application unusably slow.

Use efficient event construction and avoid expensive serialization where unnecessary.

Respect the existing storage/deployment constraints.

Keep enough recent history for debugging according to the repository's existing model.

Do not prematurely sacrifice diagnostic detail merely for optimization.

If a bounded retention limit exists, make it explicit and observable.

---

# 24. FRONTEND SCOPE

This task is **backend-first**.

Do not redesign the Shorts page, player UI, or other frontend screens.

Do not add a completely new frontend logging page as part of this task.

The important frontend-related requirement is that the existing/future Logs page can consume **one backend stream containing both server and app logs**.

If a tiny frontend compatibility change is absolutely required because the current client assumes an old response shape, keep it minimal and explain it.

Otherwise leave frontend UI work for a separate task.

---

# 25. TESTING — ACTUALLY VERIFY IT

After implementation, run the relevant tests/commands available in the repository.

At minimum verify:

### Unified server + app logs

Generate a backend request and an app log for the same/general operation and verify they appear in the same retrieval stream with source information and correct chronological ordering.

### Successful AnimeXIn flow

Verify the complete real trace is visible.

### AnimeXIn/provider failure

Reproduce or simulate a real failure and verify the exact failing stage, exception, and context are logged.

### yt-dlp success

Verify extractor, formats, quality candidates, playable URL information, and final result are visible.

### yt-dlp failure

Verify stderr, exit code, exception, retries, and traceback are visible.

### Metadata failure

Verify the exact metadata extraction stage is visible.

### Playback URL failure

Verify provider → extraction → validation → fallback is traceable.

### Shorts feed/discovery

Verify feed exhaustion, discovery trigger, ytfetcher result, fallback result, insertion, and next-page behavior can be followed by operation ID.

### Concurrent operations

Trigger multiple operations and verify their traces can be separated.

### Existing API compatibility

Verify existing `/api/app-logs` consumers still work where practical.

Do not claim tests passed if they were not actually run.

If an external provider cannot be reached from the environment, clearly report that and test all locally testable pieces.

---

# 26. DO NOT PATCH RANDOMLY

This is not an invitation to add a few `print()` statements around the currently failing code.

Build a coherent observability architecture that works across the entire backend.

Do not duplicate logging systems.

Do not create a second server-log database if the existing one can be extended correctly.

Do not create separate app/server pages or separate retrieval contracts when they can be unified.

Do not rewrite unrelated application functionality.

---

# 27. PRESERVE EXISTING APPLICATION BEHAVIOR

This task is primarily observability.

Do NOT intentionally change:

- API behavior
- provider priority
- metadata semantics
- playable URL semantics
- Shorts ranking behavior
- Shorts discovery behavior
- Shorts prefetch behavior
- quality selection behavior
- existing dependency choices

unless a change is strictly required for correct logging/correlation.

If you discover an unrelated bug, document it rather than silently changing unrelated behavior.

---

# 28. ACCEPTANCE CRITERION

The implementation is successful only if this becomes possible:

> A user reports: `AnimeXIn playback failed.`
>
> A developer opens the existing Logs system, sees one chronological stream containing both server and app events, finds the relevant operation ID, follows the A→Z trace, expands the detailed event data, and determines the exact request/parser/provider/extractor/validation step that failed — including the real exception/traceback and retry/fallback path where available.

The same standard must apply to `yt-dlp`, metadata extraction, playback URL refresh, Shorts discovery, and other important backend operations.

---

# 29. FINAL VERIFICATION CHECKLIST

Before finishing, verify all applicable items:

- [ ] Entire repository was inspected before implementation.
- [ ] Existing logging architecture was understood.
- [ ] Existing app-log infrastructure was reused/extended where appropriate.
- [ ] Server logs and app logs are represented by one common event model.
- [ ] Server logs and app logs are exposed through one unified chronological stream.
- [ ] Each event retains its real source/origin.
- [ ] Operation/trace IDs exist for meaningful backend operations.
- [ ] App events are correlated with backend operations where possible.
- [ ] AnimeXIn has A→Z execution tracing.
- [ ] Provider resolution has A→Z tracing.
- [ ] Playable URL extraction has A→Z tracing.
- [ ] Existing `yt-dlp` dependency/version is preserved.
- [ ] Existing `yt-dlp` extraction mechanism is preserved.
- [ ] All available quality/playable URL results are preserved and observable.
- [ ] Metadata dependency/behavior is preserved.
- [ ] Network requests, retries, and timeouts are visible.
- [ ] Playwright/browser activity is visible where applicable.
- [ ] Shorts feed/discovery/replenishment is traceable.
- [ ] Exceptions retain type/message/traceback.
- [ ] Server HTTP events are captured where appropriate.
- [ ] Secrets are redacted.
- [ ] No artificial category wall hides chronological events.
- [ ] Existing `/api/app-logs` compatibility is preserved where practical.
- [ ] Live polling/retrieval remains functional.
- [ ] No unnecessary frontend redesign was performed.
- [ ] Unified server + app log retrieval was actually tested.
- [ ] Relevant backend tests/commands were actually executed.
- [ ] No fake successful test results were reported.

## Final instruction to OpenCode

**Do not stop at analysis. Inspect the repository, implement the complete backend observability redesign, integrate server + app logs into one unified chronological stream, test it, and leave the repository in a working state.**