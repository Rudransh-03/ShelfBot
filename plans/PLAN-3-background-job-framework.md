# PLAN 3 — Background Job Framework (unified, cancellable jobs)

> **📍 Navigation:** [Index](README.md) · [How to execute](EXECUTION-PROTOCOL.md) · [Tracker](TRACKER.md) · Prev: [PLAN-2](PLAN-2-indexing-throughput-incremental-metadata.md) · Next: [PLAN-4](PLAN-4-observability-diagnostics.md)
> **Execute when:** Decision **D-1 ≠ GO** ([Decision log](README.md#decision-log)). If D-1 = GO this design lands natively in Python — [MIGRATION §8](MIGRATION-java-to-fastapi.md#8-background-jobs--async-processing).
> **Executor:** follow [EXECUTION-PROTOCOL.md](EXECUTION-PROTOCOL.md); record in [TRACKER.md](TRACKER.md#plan-3). The response-shape freeze (edge case 1) is the top failure mode — the renderer's poll loops are the spec.
>
> Depends on PLAN-2 only for the scanner `…One` methods (watcher hook). It can
> be executed independently if PLAN-2 hasn't run — nothing here calls them.

## Goal

One `JobManager` owns all long-running backend work. Concretely:

1. Replace the three copy-pasted job trios in `ApiServer`
   (indexing: `indexingRunning`/`indexingStatus`/`bgExecutor`;
   deadline scan: `deadlineScanRunning`/`deadlineScanStatus`/`deadlineExecutor`;
   extraction: `extractionRunning`/`extractionResult`/`extractionExecutor`)
   with jobs submitted to a single manager.
2. Add the missing **cancellation for indexing** (today a runaway index of a
   huge folder can only be stopped by quitting the app), exposed as
   `DELETE /api/index`.
3. Move `recomputeMembership()` — a full-library, minutes-at-scale operation —
   **off HTTP request threads** in `/api/clients` handlers.
4. `GET /api/jobs` — one place to see everything running.

**Hard constraint:** every existing endpoint's response JSON must stay
byte-shape compatible. The renderer polls `/api/index`, `/api/deadlines/scan`,
`/api/extract` with fixed field names (`running`, `hasRun`, `progress`,
`result`, `rows`, …). The job framework sits BEHIND those endpoints.

## Files to touch

1. NEW `backend/src/main/java/com/localfilebrain/jobs/JobManager.java`
2. NEW `backend/src/main/java/com/localfilebrain/jobs/Job.java`
3. `backend/src/main/java/com/localfilebrain/ingestion/IngestionPipeline.java`
   (cancellation hook only)
4. `backend/src/main/java/com/localfilebrain/api/ApiServer.java`
5. `ui/src/renderer/src/api/client.js` (one new method: `cancelIndex()`)
6. NEW `backend/src/test/java/com/localfilebrain/jobs/JobManagerTest.java`
7. NEW/extended pipeline cancellation test.

Do NOT touch: `FileWatcher`, `Main`, proxy, `QueryEngine`.

## Steps, in order

### Step 1 — Job + JobManager

`Job.java`:

```java
package com.localfilebrain.jobs;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One tracked unit of background work. Thread-safe via atomics. */
public final class Job {
    public enum Type { INDEXING, DEADLINE_SCAN, EXTRACTION, MEMBERSHIP_RECOMPUTE }
    public enum State { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    private final String id;                 // UUID
    private final Type type;
    private final Instant createdAt = Instant.now();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);
    private final AtomicReference<Map<String, Object>> progress = new AtomicReference<>(null);
    private final AtomicReference<String> error = new AtomicReference<>(null);
    // + ctor, getters, requestCancel(), isCancelRequested(), setters used by JobManager
    // + Map<String,Object> toMap() for the /api/jobs endpoint
}
```

`JobManager.java`:

```java
package com.localfilebrain.jobs;

/**
 * Single owner of background work. One dedicated single-thread executor PER
 * Job.Type (indexing must not queue behind extraction and vice versa — this
 * preserves today's independent-executor behavior), single-flight per type
 * (submitting while a job of that type is RUNNING/QUEUED returns empty).
 */
public final class JobManager implements AutoCloseable {
    /** The work body. Poll job.isCancelRequested() at natural boundaries. */
    @FunctionalInterface public interface Body { void run(Job job) throws Exception; }

    public Optional<Job> submit(Job.Type type, Body body) { … }
    public Optional<Job> get(String id) { … }
    public Optional<Job> activeOfType(Job.Type type) { … }
    public List<Job> list() { … }                        // newest first, keep last ~50
    public boolean cancel(String id) { … }               // sets flag; body must honor it
    @Override public void close() { /* requestCancel all running, shutdown executors */ }
}
```

Implementation notes:
- Executors: `Executors.newSingleThreadExecutor` per type with named daemon
  threads (`"rudo-job-indexing"` etc.) — exactly mirrors the three existing
  executors it replaces.
- Single-flight: `submit` checks `activeOfType(type)`; if present return
  `Optional.empty()` (callers translate to the existing 409 responses).
- State transitions inside the wrapper runnable:
  QUEUED→RUNNING → body runs → SUCCEEDED, or CANCELLED if
  `isCancelRequested()` at the end, or FAILED with `error` on throwable.
- Retain finished jobs in a bounded deque (last 50) for `/api/jobs`.

### Step 2 — Cancellable IngestionPipeline

Add an overload `run(ProgressListener listener, java.util.function.BooleanSupplier cancelled)`;
the existing `run(listener)` delegates with `() -> false`.

Inside the worker submit loop, before processing each file:

```java
if (cancelled.getAsBoolean()) { lst.onFileEnd(fileId); return; } // skip remaining
```

and in the batch/commit logic (if PLAN-2 landed) make sure the final
`flushPending` still runs in `finally` — cancellation must never skip the
commit of already-processed files. The `IngestionResult` of a cancelled run is
whatever was processed so far (no new field needed — the extraction feature
already models "cancelled" in its own status; for indexing, the UI simply sees
the job finish early with a smaller `filesProcessed`).

### Step 3 — ApiServer: delegate the three trios to JobManager

1. Construct `private final JobManager jobs = new JobManager();` in the
   constructor; call `jobs.close()` in `stop()`. Delete the three
   executor fields + their `AtomicBoolean running` flags. KEEP the
   status/progress `AtomicReference` fields for now — they feed the legacy
   response shapes (`IndexingStatus`, `DeadlineScanState`, extraction result
   map) and changing them would ripple into the UI. The job body updates BOTH
   the legacy refs and `job.setProgress(...)`.
2. `handleIndex` POST: `jobs.submit(Job.Type.INDEXING, job -> { …existing body…,
   pipeline.run(listener, job::isCancelRequested) })`; empty Optional → the
   existing 409 `{"error":"Indexing already in progress"}`. Include
   `"jobId": job.id()` in the 202 response (additive field — safe).
3. `handleIndex` add DELETE branch:
   ```java
   if (isMethod(ex, "DELETE")) {
       var active = jobs.activeOfType(Job.Type.INDEXING);
       active.ifPresent(j -> jobs.cancel(j.id()));
       sendJson(ex, 200, map("cancelling", active.isPresent()));
       return;
   }
   ```
   (mirror of the extraction cancel contract at `handleExtract` DELETE).
4. `handleDeadlineScan`, `handleExtract`: same mechanical delegation; the
   extraction body already polls `extractionCancel` — replace that
   `AtomicBoolean` with `job::isCancelRequested` and route the existing
   DELETE branch through `jobs.cancel`.
5. `recomputeMembership()` call sites in `handleClients`
   (`ApiServer.java` ~lines 1619, 1625, 1674, 1693, 1702) and the post-index
   block (~line 439): replace direct calls with
   `jobs.submit(Job.Type.MEMBERSHIP_RECOMPUTE, job -> recomputeMembership())`.
   Response compatibility:
   - `/api/clients/recompute` currently returns
     `{assigned, conflicted, unmatched}`. To preserve the shape for the ONE UI
     caller (`ClientSuggestionModal.jsx:70`, which awaits it then refreshes),
     use a bounded wait: submit the job, then poll its state up to **15 s**
     (sleep 200 ms loop); if it finished, return the real counts (store the
     `MembershipEngine.Result` on the job via `setProgress`); if still
     running, return `{"assigned":0,"conflicted":0,"unmatched":0,"async":true,"jobId":…}`.
     The modal ignores the counts today beyond logging — verify with
     `grep -n "recomputeClients" ui/src/renderer/src/components/ClientSuggestionModal.jsx`
     and read the surrounding 10 lines before assuming.
   - The other `recomputeMembership()` sites (`create/edit/delete client`,
     `accept suggestion`, post-index) don't return counts to the client —
     fire-and-forget submit is fine there. If a MEMBERSHIP job is already
     running, `submit` returns empty — that's acceptable (the running pass will
     pick up most changes; the next explicit recompute catches the rest). Log it.
6. New endpoint `GET /api/jobs` → `{"jobs":[job.toMap()...]}` and
   `DELETE /api/jobs/{id}` → `{"cancelling":bool}`. Register in
   `registerRoutes()` BEFORE `/api/...` prefixes that could shadow (order
   doesn't matter for distinct paths with `HttpServer`, but keep `/api/jobs`
   its own context).

### Step 4 — UI: cancel button plumbing (minimal)

- `ui/src/renderer/src/api/client.js`: add
  `cancelIndex() { return this._r('/api/index', { method: 'DELETE' }) }`.
- Do NOT redesign any view. Optional (only if trivially greppable): the
  indexing progress panel lives in `Sidebar.jsx` / `Library.jsx` — if there is
  an existing "View status" panel component with a clear place for a button,
  add a small "Stop" button calling `api.cancelIndex()`; otherwise skip the UI
  button entirely and leave the endpoint for the next UI pass. The plan's
  acceptance does not require the button.

### Step 5 — Tests

`JobManagerTest.java` (pure unit, fast):
- submit runs body, state transitions QUEUED/RUNNING→SUCCEEDED.
- second submit of same type while running → `Optional.empty()`.
- different types run concurrently (latch-based, like the supervisor tests).
- cancel sets the flag; a body that polls it exits early → state CANCELLED.
- body throwing → FAILED with error message.
- `list()` bounded at 50.

Pipeline cancellation test (extend the PLAN-2 pipeline test or create one with
the same fake-embedding fixture): index 10 files where the fake embedder sleeps
50 ms per file; request cancel after the 3rd `onProgress` tick; assert the run
returns with `filesProcessed < 10` and every processed file is consistent
(INDEXED ⇒ searchable).

## Edge cases a weaker model would miss

1. **Response-shape freeze.** The renderer's poll loops
   (`AppContext.jsx` busy/deadline timers, `Extract.jsx`/`BulkQA.jsx` poll
   functions) read exact field names. Adding fields is safe; renaming or
   removing is not. Never return the raw `Job.toMap()` from the legacy
   endpoints.
2. **The legacy `AtomicReference` status fields must keep working** — the GET
   branches of `/api/index`, `/api/deadlines/scan`, `/api/extract` build their
   responses from them. The job body updates them exactly where the old
   executor body did (same `finally` blocks clearing progress).
3. **Cancellation is cooperative.** Never call `Future.cancel(true)` /
   interrupt the worker — Lucene/SQLite mid-write interrupts are how you
   corrupt state. Only the `BooleanSupplier` polling pattern.
4. **Indexing cancel + PLAN-2 batching**: cancelled run must still flush the
   pending metadata batch (`finally`), or processed files become
   INDEXED-in-Lucene-only or vice versa.
5. **`stop()` ordering**: `jobs.close()` must run before the stores close
   (ApiServer.stop currently shuts executors then closes chatStore — put
   `jobs.close()` where the executor shutdowns were).
6. **Single-flight race**: two concurrent POST /api/index must yield exactly
   one job — implement `submit` check-and-insert under one lock
   (`synchronized` on the manager), not check-then-act.
7. **Bounded wait loop for recompute** must not hold any lock while sleeping,
   and must run on the HTTP thread only up to the 15 s cap — the job keeps
   running server-side after the response either way.
8. **`handleClients` create with identifiers** calls recompute immediately
   after `createClient` — with async submit, the response `{"id":…}` returns
   before tagging finishes. That is an intended behavior change (the UI
   refreshes lists on demand); note it in the final summary, don't "fix" it.

## Acceptance criteria

1. `grep -n "newSingleThreadExecutor" backend/src/main/java/com/localfilebrain/api/ApiServer.java`
   → no matches (all three moved into JobManager).
2. `grep -n "recomputeMembership()" backend/src/main/java/com/localfilebrain/api/ApiServer.java`
   → only inside the JobManager submit bodies / the bounded-wait helper.
3. `DELETE /api/index` during a run stops it early (pipeline cancellation test
   passes); `GET /api/jobs` lists jobs with state.
4. All new JobManager tests pass; full backend suite 0 failures.
5. UI tests + `npm run build` green; `client.js` has `cancelIndex()`.
6. Legacy shapes intact: run the app (or eyeball the GET handlers) —
   `/api/index` GET still returns `running/hasRun/progress/activeFiles/result`
   exactly as before, plus nothing removed.
