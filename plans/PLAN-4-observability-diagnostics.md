# PLAN 4 — Observability & Diagnostics (local-only)

> **📍 Navigation:** [Index](README.md) · [How to execute](EXECUTION-PROTOCOL.md) · [Tracker](TRACKER.md) · Prev: [PLAN-3](PLAN-3-background-job-framework.md) · Next: [PLAN-5](PLAN-5-versioned-migrations.md)
> **Execute when:** **ALWAYS** — this plan runs regardless of Decision D-1 ([Decision log](README.md#decision-log)). The Electron half survives the migration untouched; the backend half re-ports per [MIGRATION §10](MIGRATION-java-to-fastapi.md#10-logging-monitoring-observability-error-handling).
> **Executor:** follow [EXECUTION-PROTOCOL.md](EXECUTION-PROTOCOL.md); record in [TRACKER.md](TRACKER.md#plan-4). Privacy rules (never export DBs/document text/tokens) are hard requirements, not suggestions.

## Goal

Every production problem must leave an artifact a user can hand to support, and
the app must be able to package those artifacts in one click. Explicitly
**local-only**: no remote telemetry, no crash-analytics SaaS (both are out of
scope per the product's privacy posture). What exists today: a rolling backend
file log (`<dataDir>/logs/rudo-backend.log`, added in Milestone 2) and
electron-log for auto-updater events only. What's missing:

1. Backend timing/throughput metrics (query latency, LLM call duration,
   indexing throughput) — currently zero instrumentation.
2. A recent-errors ring buffer surfaced over HTTP.
3. `GET /api/diagnostics` (snapshot) + a one-click **diagnostics bundle export**
   (zip) reachable from Settings.
4. Electron main-process and renderer errors written to a log file (today
   `console.*` in a packaged app goes nowhere, and renderer crashes vanish).

## Files to touch

Backend:
1. NEW `backend/src/main/java/com/localfilebrain/util/Metrics.java`
2. NEW `backend/src/main/java/com/localfilebrain/util/RingLogAppender.java`
3. `backend/src/main/resources/logback.xml` (register the ring appender)
4. `backend/src/main/java/com/localfilebrain/api/ApiServer.java`
   (instrument query path; add `/api/diagnostics` + `/api/diagnostics/export`)
5. `backend/src/main/java/com/localfilebrain/ingestion/IngestionPipeline.java`
   (record throughput metrics — 3 lines)
6. NEW tests: `MetricsTest.java`, `RingLogAppenderTest.java`,
   `DiagnosticsBundleTest.java`

Electron/UI:
7. `ui/src/main/index.js` (route main logs to electron-log file; IPC for
   renderer errors + export-diagnostics save dialog)
8. `ui/src/preload/index.js` (expose `logRendererError`, `exportDiagnostics`)
9. `ui/src/renderer/src/components/ErrorBoundary.jsx` (report into the new IPC)
10. `ui/src/renderer/src/main.jsx` (global `window.onerror` /
    `unhandledrejection` hooks)
11. `ui/src/renderer/src/views/Settings.jsx` (an "Export diagnostics" button)
12. `ui/src/renderer/src/api/client.js` (`getDiagnostics()`, `exportDiagnostics()`)

## Steps, in order

### Step 1 — `Metrics` (tiny, dependency-free)

```java
package com.localfilebrain.util;

/**
 * In-process metrics: named counters and duration recorders with simple
 * percentile snapshots. Deliberately tiny — no Micrometer, no export; the
 * numbers surface only through /api/diagnostics and periodic log lines.
 * Thread-safe. Durations keep a bounded ring of the last 512 samples per name.
 */
public final class Metrics {
    public static void inc(String counter) { … }                  // ConcurrentHashMap<String, LongAdder>
    public static void recordMs(String timer, long ms) { … }      // ring buffer per timer
    public static AutoCloseable time(String timer) { … }          // try-with-resources helper
    public static Map<String, Object> snapshot() { … }            // counters + per-timer {count, p50, p95, max}
    static void resetForTests() { … }
}
```

Percentiles: copy the samples ring, sort, index at `(n*50)/100` and
`(n*95)/100`. Keep it dumb.

### Step 2 — Instrument the hot paths (names are the contract)

- `ApiServer.handleQuery` / `handleQueryStream`: wrap the
  `answerInConversation` call in `try (var t = Metrics.time("query.total"))`,
  and `Metrics.inc("query.count")`; on catch `Metrics.inc("query.error")`.
- `GPT4oMiniClient`: in the two places that open connections (the streaming
  `doStream` and the non-streaming request method), time as `llm.call` and
  count `llm.error` on LLMException. Keep it to 4 lines total; do not refactor
  the client.
- `IngestionPipeline.run`: after the run completes, `Metrics.recordMs("index.run", duration)`,
  `Metrics.inc("index.files.processed")` × processedCount is wasteful — instead
  add a counter-with-value: simplest is `Metrics.recordMs("index.files.perRun", processedCount)`
  (abusing the timer as a histogram is fine; note it in a comment).
- `ExtractionService.run`: `Metrics.recordMs("extract.run", elapsed)` and
  `Metrics.inc("extract.truncatedBatches")` per truncated batch.

### Step 3 — `RingLogAppender`

A Logback appender keeping the last 200 WARN/ERROR events in a static ring:

```java
public final class RingLogAppender extends ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> {
    private static final java.util.concurrent.ConcurrentLinkedDeque<Map<String,String>> RING = …;
    // append(): if level >= WARN, push {ts, level, logger, message(truncated to 500 chars)}; trim to 200.
    public static java.util.List<Map<String,String>> recent() { … }
    public static void clearForTests() { … }
}
```

Register in `logback.xml`:

```xml
<appender name="RING" class="com.localfilebrain.util.RingLogAppender"/>
...
<root level="INFO"> <appender-ref ref="CONSOLE"/> <appender-ref ref="FILE"/> <appender-ref ref="RING"/> </root>
```

**Privacy rule:** the ring stores the formatted message only — messages in this
codebase already avoid document content; do NOT add stack traces' cause chains
beyond `throwable.toString()`.

### Step 4 — `GET /api/diagnostics` (snapshot)

New handler in `ApiServer` (register `"/api/diagnostics"`; the export route
below must be registered FIRST as `"/api/diagnostics/export"` — with
`com.sun.net.httpserver`, the longest matching context wins, so both work, but
register both explicitly). Response:

```json
{
  "app":     {"version":"1.0.0","platform":"mac os x","javaVersion":"17.x"},
  "dataDir": "/Users/x/Library/Application Support/Rudo",
  "stores":  {"indexedFiles":123,"failedFiles":2,"totalChunks":4567,"vectorCount":4567},
  "memory":  {"heapUsedMb":123,"heapMaxMb":512},
  "metrics": { …Metrics.snapshot()… },
  "recentErrors": [ …RingLogAppender.recent()… ]
}
```

Sources: `metadataStore.countIndexed()/countFailed()/getTotalChunks()`,
`vectorStore.count()`, `Runtime.getRuntime()`, `config.getDataRoot()`.
Wrap each store call in try/catch → `-1` on failure (diagnostics must never 500
because a subsystem is broken — that is exactly when it's needed).

### Step 5 — `POST /api/diagnostics/export` (bundle)

Builds a zip in `config.getDataRoot().resolve("tmp")` named
`rudo-diagnostics-<yyyyMMdd-HHmmss>.zip` and returns `{"path": "<abs path>"}`.

Contents (use `java.util.zip.ZipOutputStream`, no new dependency):
- `diagnostics.json` — the Step-4 snapshot, pretty-printed.
- `logs/` — every file in `config.getDataRoot().resolve("logs")` (backend
  rolling logs + the UI log from Step 7), each capped: if a file exceeds 5 MB,
  include only its LAST 5 MB (seek; prepend a `--- truncated ---` line).
- `config.properties` — the data-dir config file if present, with any line
  containing `key` or `token` (case-insensitive) replaced by `<redacted>`.
  (Today's config keys hold no secrets except a possible legacy
  `openai.api.key` — that is exactly what this redaction is for.)

**Never include:** the SQLite DBs, the Lucene index, chat content, or the auth
token file. Document text must not be exportable.

Extract the zip-building into a package-visible static method
`DiagnosticsBundle.write(Path outFile, Path logsDir, Path configFile, byte[] diagnosticsJson)`
in a new small class next to ApiServer, so it is unit-testable without HTTP.

### Step 6 — Electron: logs that actually persist + renderer error capture

In `ui/src/main/index.js`:

1. At startup (inside the existing `app.whenReady` or module top), route
   electron-log to the SAME logs directory the backend uses and mirror console:
   ```js
   try {
     const log = require('electron-log')
     log.transports.file.resolvePathFn = () => join(LOG_DIR, 'rudo-ui.log')
     log.transports.file.level = 'info'
     Object.assign(console, { log: log.log, warn: log.warn, error: log.error })
   } catch { /* electron-log missing in some dev setups — keep console */ }
   ```
   (electron-log is already a dependency; the updater block already lazy-requires
   it — reuse the same guarded pattern. Make sure this runs AFTER `LOG_DIR` is
   defined and `mkdirSync(LOG_DIR, {recursive:true})` best-effort.)
2. IPC `log:renderer-error`: `ipcMain.on('log:renderer-error', (_e, payload) => console.error('[renderer]', String(payload?.message||''), String(payload?.stack||'').slice(0, 4000)))`.
3. IPC `diagnostics:export`: calls the backend
   (`fetch http://localhost:${lastApiPort}/api/diagnostics/export` with the
   `X-Shelfbot-Token` header — copy the pattern from `pushTokenToBackend`),
   then shows a save dialog (`dialog.showSaveDialog`, default name = zip
   basename) and copies the file there (`copyFileSync`), returning
   `{ok, path}` — mirror the existing `export:file` handler's structure.

In `ui/src/preload/index.js` add:
```js
logRendererError:  (payload) => ipcRenderer.send('log:renderer-error', payload),
exportDiagnostics: ()        => ipcRenderer.invoke('diagnostics:export'),
```

In `ui/src/renderer/src/main.jsx` (top level, before render):
```js
window.addEventListener('error', (e) => window.electron?.logRendererError?.({ message: e.message, stack: e.error?.stack }))
window.addEventListener('unhandledrejection', (e) => window.electron?.logRendererError?.({ message: String(e.reason), stack: e.reason?.stack }))
```

In `ErrorBoundary.jsx`'s `componentDidCatch`, add the same
`window.electron?.logRendererError?.(...)` call (keep whatever it renders now).

### Step 7 — Settings button + client methods

- `client.js`:
  `getDiagnostics() { return this._r('/api/diagnostics') }` and no client
  method for export (it goes through IPC, not the renderer's fetch — the main
  process owns the save dialog).
- `Settings.jsx`: find the existing danger/maintenance section (read the file
  first; it is one default-exported component, ~511 lines). Add a plain button
  consistent with neighboring buttons' classNames:
  "Export diagnostics" → `const r = await window.electron.exportDiagnostics()`
  → toast success with the saved path or the error (use the existing toast
  mechanism from `useApp()` — grep for `toast` in the file and copy the call
  style).

### Step 8 — Tests

Backend:
- `MetricsTest`: counters add up; `recordMs` p50/p95 sane for a known
  distribution (record 1..100 → p50≈50, p95≈95); snapshot contains both;
  `time()` records >= elapsed.
- `RingLogAppenderTest`: log 250 warns via a Logger configured
  programmatically with the appender → `recent()` has 200, newest last;
  INFO events ignored.
- `DiagnosticsBundleTest`: build a bundle from a temp logs dir (two files, one
  6 MB → truncated to 5 MB tail) + a config containing `openai.api.key=sk-x`
  → zip entries exist; extracted config has `<redacted>`; no other files leak.

UI: no new UI tests required (node:test can't exercise IPC); rely on build +
existing suites.

## Edge cases a weaker model would miss

1. **Diagnostics must never throw** — every data source in the snapshot gets
   its own try/catch with a fallback value. A broken vector store is the #1
   moment someone exports diagnostics.
2. **Zip of the live log file**: the backend is writing to
   `rudo-backend.log` while you zip it — read with
   `Files.newInputStream` (fine on all OSes for a file being appended; on
   Windows Logback's default is also shared-read). Do not try to lock/rotate.
3. **Token header** on the Electron→backend export call — without
   `X-Shelfbot-Token` the request 403s in packaged builds (works in dev,
   breaks in prod: classic trap). Copy `pushTokenToBackend`'s header usage.
4. **Console reassignment order** in `index.js`: assign before the first
   `console.log` you care about, but AFTER `LOG_DIR` exists; keep the
   try/catch so a missing electron-log never crashes startup.
5. **Do not log the payload of queries** — instrument timings only. `Query:`
   lines already log the question text at INFO (pre-existing); do not add
   more content-bearing logs, and the ring buffer only captures WARN+.
6. **`register("/api/diagnostics", …)` context matching**: register
   `/api/diagnostics/export` as its own context; `com.sun.net.httpserver`
   longest-prefix matching would otherwise route export POSTs into the
   snapshot handler.
7. **electron-log `resolvePathFn`** is the v5 API (`package.json` has ^5.1.7).
   Do not use the removed v4 `file.resolvePath`.
8. **5 MB tail truncation** must not split the file in memory: open a
   `RandomAccessFile`/`SeekableByteChannel`, seek to `size-5MB`, stream.

## Acceptance criteria

1. `curl -s localhost:9876/api/diagnostics | jq .` shows app/stores/memory/
   metrics/recentErrors with sane values after asking one chat question
   (`metrics."query.total".count >= 1`).
2. `POST /api/diagnostics/export` returns a path; the zip contains
   `diagnostics.json`, `logs/rudo-backend.log`, and (packaged/dev with UI log)
   `logs/rudo-ui.log`; `config.properties` inside has no `key`/`token` values.
3. The three backend test classes pass; full backend suite 0 failures.
4. `ui`: build green; a thrown error in the renderer (temporarily add
   `throw new Error('test')` in a button handler, then remove) appears in
   `<dataDir>/logs/rudo-ui.log`.
5. Settings shows "Export diagnostics"; clicking it produces a zip via save
   dialog (manual check).
