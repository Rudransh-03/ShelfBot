# PLAN 2 — Indexing Throughput & Incremental Metadata Processing

> **📍 Navigation:** [Index](README.md) · [How to execute](EXECUTION-PROTOCOL.md) · [Tracker](TRACKER.md) · Prev: [PLAN-1](PLAN-1-scalable-query-execution.md) · Next: [PLAN-3](PLAN-3-background-job-framework.md)
> **Execute when:** Decision **D-1 ≠ GO** ([Decision log](README.md#decision-log)). If D-1 = GO, the Lucene-specific steps die with Lucene; the carried-over requirements are listed in [MIGRATION §14](MIGRATION-java-to-fastapi.md#14-disposition-of-plan-15-under-the-migration).
> **Executor:** follow [EXECUTION-PROTOCOL.md](EXECUTION-PROTOCOL.md); record results in [TRACKER.md](TRACKER.md#plan-2). The crash-ordering invariant in Step 2 is the heart of this plan — read its edge cases twice.

## Goal

Indexing N files must do O(N) total work, and one file-watcher event must do
O(1) work. Today:

- **One Lucene `commit()` + searcher refresh per file** —
  `VectorStore.replaceBySourceFile` (`VectorStore.java:209-210`) is called once
  per file by `IngestionPipeline.processFile` (`IngestionPipeline.java:453`).
  A commit is an fsync-heavy segment flush; 100k files = 100k fsyncs.
- **O(N²) budget accounting** — `enforceBudget`
  (`IngestionPipeline.java:492-510`) calls
  `metadataStore.sumIndexedTokens()` (a full-table `SUM`) once **per file**.
- **Three full-library scans per watcher event** — the post-index hook wired in
  `Main.tryStartWatcher` runs `DocumentTypeClassifier.classifyAll`,
  `LocalDateScanner.scanAll`, `LocalEntityScanner.scanAll` on EVERY single
  file event; each iterates every `FileRecord` row.
- **Silent 1000-chunk truncation** — `VectorStore.getChunksForFile` caps its
  term query at 1000 hits (`VectorStore.java:296`), but a max-size file
  (500k tokens ≈ 2M chars at 1800-char chunks) has ~1,250 chunks; the tail is
  silently dropped in file-scope answers, summaries, and reorg healing.

## Files to touch

1. `backend/src/main/java/com/localfilebrain/storage/VectorStore.java`
2. `backend/src/main/java/com/localfilebrain/ingestion/IngestionPipeline.java`
3. `backend/src/main/java/com/localfilebrain/ingestion/IndexMetadataStore.java`
4. `backend/src/main/java/com/localfilebrain/ingestion/DocumentTypeClassifier.java`
5. `backend/src/main/java/com/localfilebrain/timeline/LocalDateScanner.java`
6. `backend/src/main/java/com/localfilebrain/client/LocalEntityScanner.java`
7. `backend/src/main/java/com/localfilebrain/Main.java` (watcher hook only)
8. Tests (see Step 6).

Do NOT touch: `ApiServer` job wiring, `FileWatcher`, `QueryEngine`,
`MembershipEngine` (its async move is Plan 3).

## Steps, in order

### Step 1 — VectorStore: caller-controlled commits + bigger per-file cap

1. Add a no-commit variant and a public commit method:

   ```java
   /** Same as {@link #replaceBySourceFile(String, List, List)} but lets the
    *  caller defer the (fsync-heavy) commit — used by bulk indexing, which
    *  commits once per batch of files instead of once per file. The delete+add
    *  pair still holds the writer monitor, so atomic replacement is unchanged. */
   public synchronized void replaceBySourceFile(String absoluteFilePath,
                                                List<DocumentChunk> chunks,
                                                List<float[]> embeddings,
                                                boolean commit) { … }

   /** Commits pending writes and refreshes searchers. Called by bulk indexing
    *  at batch boundaries and at end-of-run. */
   public synchronized void commitAndRefresh() {
       try { writer.commit(); searchers.maybeRefresh(); }
       catch (IOException e) { throw new VectorStoreException("Failed to commit", e); }
   }
   ```

   Implement by moving the body of the existing `replaceBySourceFile` into the
   4-arg version, with `if (commit) { writer.commit(); searchers.maybeRefresh(); }`
   at the end; the existing 3-arg method delegates with `commit=true`.
   Leave `upsert`, `deleteAll`, `deleteBySourceFile` unchanged (they are not on
   the bulk path).

2. Fix the truncation cap. Add one constant and use it in all three per-file
   fetch sites (`getChunksForFile`, `findPathsByFileName` may keep 1000 — it
   only collects distinct paths — but `getChunkVectorsForFile` must change):

   ```java
   // Upper bound on chunks fetched per file. Must exceed the worst legal case:
   // MAX_TOKENS_PER_FILE (500k tokens ≈ 2M chars) / ~1600 effective chars per
   // chunk ≈ 1,250 chunks. 4000 leaves generous headroom.
   private static final int MAX_CHUNKS_PER_FILE_FETCH = 4_000;
   ```

   Replace the literal `1000` in `getChunksForFile` (line ~296) and
   `getChunkVectorsForFile` (line ~383) with the constant.

### Step 2 — IngestionPipeline: batched commits with crash-safe metadata ordering

**Correctness rule (the whole point of this step):** a file's `INDEXED`
metadata row may only be written AFTER the Lucene commit that contains its
chunks. Otherwise a crash between upsert and commit leaves metadata claiming
INDEXED while the chunks are gone — and the timestamp/hash check would skip the
file forever.

In `IngestionPipeline`:

1. Add a constant `private static final int COMMIT_EVERY_FILES = 25;`

2. Add a per-run pending-success buffer. Inside `run(ProgressListener)`, before
   the worker loop:

   ```java
   List<PendingSuccess> pending = Collections.synchronizedList(new ArrayList<>());
   AtomicInteger sinceCommit = new AtomicInteger(0);
   ```

   with `private record PendingSuccess(Path file, BasicFileAttributes attrs,
   String hash, int chunkCount, long tokenCount) {}`.

3. Thread a `boolean bulkMode` through the guarded index path. Change
   `indexFileGuarded(Path, FileProgress)` to
   `indexFileGuarded(Path file, FileProgress fp, boolean deferCommit)` and
   `processFile(...)` likewise; `processFile` calls
   `vectorStore.replaceBySourceFile(absolutePath, chunks, embeddings, !deferCommit)`.

4. In `indexFileGuarded`, when `deferCommit` is true, DO NOT call
   `recordSuccess` — instead return the attrs/hash/counts to the caller (extend
   `FileOutcome` with nullable `attrs`/`hash` fields, or add a small holder).
   When `deferCommit` is false (watcher path), behavior is exactly as today:
   commit inside `replaceBySourceFile`, then `recordSuccess` inside the lock.

5. In the worker lambda inside `run(...)`, on an `INDEXED` outcome with
   `deferCommit=true`: add to `pending`, and if
   `sinceCommit.incrementAndGet() >= COMMIT_EVERY_FILES`, call a new
   synchronized method:

   ```java
   private synchronized void flushPending(List<PendingSuccess> pending, AtomicInteger sinceCommit) {
       List<PendingSuccess> batch;
       synchronized (pending) {
           if (pending.isEmpty()) { sinceCommit.set(0); return; }
           batch = new ArrayList<>(pending);
           pending.clear();
       }
       vectorStore.commitAndRefresh();              // durability point
       for (PendingSuccess p : batch) {             // only now mark INDEXED
           recordSuccess(p.file(), p.attrs(), p.hash(), p.chunkCount(), p.tokenCount());
       }
       sinceCommit.set(0);
   }
   ```

6. After `pool.shutdown()` + the `f.get()` join loop, call
   `flushPending(...)` one final time inside a `finally` block so the tail
   batch is always committed even when workers threw.

7. `indexOne` (watcher / JIT-index path) calls
   `indexFileGuarded(file, FileProgress.NOOP, false)` — unchanged semantics,
   immediate commit, so a single live edit is searchable right away.

### Step 3 — Kill the O(N²) budget check

In `IndexMetadataStore`:

1. Add `private volatile long cachedTokenSum = -1;`
2. In `sumIndexedTokens()` (already `synchronized`): if `cachedTokenSum >= 0`
   return it; else run the existing SQL, store, return.
3. Invalidate (`cachedTokenSum = -1;`) at the END of every method that changes
   `token_count` or row membership: `upsert`, `delete`,
   `clearAllIndexedRecords`, `markFailed`, `renamePath`. (Cheap and safe —
   the next `sumIndexedTokens()` recomputes once.)

No change needed in `enforceBudget` itself — it becomes O(1) amortized.

### Step 4 — Single-file variants of the three scanners

For each scanner, extract the per-file body of the existing `scanAll`/
`classifyAll` loop into a public single-file method, then make the loop call it.
Signatures to add:

- `DocumentTypeClassifier`: `public static boolean classifyOne(IndexMetadataStore meta, VectorStore vectorStore, String absolutePath)` —
  returns false fast if the record is missing or already has a doc type
  (same skip conditions the loop uses at lines ~196-201).
- `LocalDateScanner`: `public boolean scanOne(String absolutePath)` — skip fast
  via the existing `meta.isDateScanned(path, hash)` check.
- `LocalEntityScanner`: `public int scanOne(String absolutePath)` — skip fast
  via its existing per-file "already scanned at this hash" check (mirror
  whatever condition its loop uses at lines ~85-90).

Keep `scanAll`/`classifyAll` public and working (the manual post-index pass in
`ApiServer.handleIndex` and the startup rescan still use them — those run once
per bulk operation, which is fine).

### Step 5 — Watcher hook uses the single-file variants

In `Main.tryStartWatcher` (the `pipeline.setPostIndexHook(path -> { … })`
lambda), replace the three `…All(...)` calls with:

```java
try { com.localfilebrain.ingestion.DocumentTypeClassifier.classifyOne(store, vectorStore, path); }
catch (Exception e) { log.debug("[watcher] doc-type pass failed: {}", e.getMessage()); }
try { new com.localfilebrain.timeline.LocalDateScanner(store, vectorStore).scanOne(path); }
catch (Exception e) { log.debug("[watcher] date pass failed: {}", e.getMessage()); }
try { new com.localfilebrain.client.LocalEntityScanner(store, vectorStore).scanOne(path); }
catch (Exception e) { log.debug("[watcher] entity pass failed: {}", e.getMessage()); }
if (store.countClients() > 0) membership.recomputeFile(path);
```

Leave `Main.runStartupReindex`'s bulk `classifyAll`/`scanAll` calls as they are.

### Step 6 — Tests

1. NEW `backend/src/test/java/com/localfilebrain/storage/VectorStoreBatchCommitTest.java`
   (use a `@TempDir` Lucene dir like `VectorStoreReplaceTest` does):
   - `deferredWritesInvisibleUntilCommit`: `replaceBySourceFile(p, chunks, embs, false)`
     → `getChunksForFile(p)` is empty; after `commitAndRefresh()` → chunks
     present.
   - `threeArgOverloadStillCommitsImmediately`: existing behavior unchanged.
   - `fetchCapAboveThousand`: insert 1,100 tiny chunks for one file (loop, batch
     the upsert), commit, assert `getChunksForFile` returns 1,100.
2. NEW `IngestionPipelineBatchTest` — hard to drive end-to-end without models;
   instead test the ordering contract at the unit level: index 3 small real
   `.txt` files through a pipeline built with a REAL VectorStore(@TempDir) and a
   fake `EmbeddingClient` (return fixed-size float[384] vectors; see
   `LocalEmbeddingClientTest` for the interface) and a real
   `IndexMetadataStore(@TempDir)`. Assert after `run()`: every file INDEXED in
   metadata AND its chunks searchable. Then a crash-simulation: subclass/wrap
   the fake embedding client to throw on file 3, run, assert files 1–2 are
   either (INDEXED and searchable) — never (INDEXED and not searchable).
3. Scanner tests: for each `…One` method, a test that (a) it processes the
   target file, (b) calling it for path X does NOT touch other rows (use two
   files, assert file Y's state unchanged), (c) already-done file returns
   fast/false. Follow the existing fixtures in `DocumentTypeClassifierTest`
   and the `LocalDateScanner` tests for construction patterns.
4. `IndexMetadataStore` token-cache test: `sumIndexedTokens` returns same value
   twice; after `upsert` of a new record with tokens, the sum reflects it
   (cache invalidated).

## Edge cases a weaker model would miss

1. **Crash-ordering is the invariant** — metadata `INDEXED` only after Lucene
   commit (Step 2). If you find this hard, do NOT "simplify" by keeping
   `recordSuccess` per-file with deferred commits; that combination silently
   loses documents on crash.
2. **The per-file lock re-check window widens**: with deferred metadata, a
   concurrent watcher event for a just-indexed file may re-index it before the
   flush writes its row. `replaceBySourceFile` is idempotent, so this is only
   wasted work — acceptable. Do not try to close the window with extra locks.
3. **`recordFailed` stays immediate** — failures don't touch Lucene, and the
   FAILED row must survive even if the run crashes later.
4. **Final flush must be in `finally`** — a worker exception must not leave
   the tail batch uncommitted-but-processed.
5. **`count()` / `resetMetadataIfDrifted` interplay**: deferred commits mean
   `vec.count()` can briefly lag metadata during a run — but startup drift
   detection runs before any indexing, so no change needed. Do not "fix" it.
6. **`commitAndRefresh` must be on the same monitor** (`synchronized`) as the
   writers — Lucene's IndexWriter is thread-safe, but the class's existing
   convention is monitor-serialized writes; keep it.
7. **Token-cache invalidation in `renamePath`**: token totals don't change on
   rename, but invalidate anyway — correctness over micro-optimization, and it
   protects against future column changes.
8. **Scanner constructors differ**: `DocumentTypeClassifier` is static-method
   style; the other two are instances taking `(meta, vectorStore)`. Match each
   file's existing style; do not unify them.
9. **`LocalEntityScanner.scanOne` return type**: its `scanAll` returns a count
   used by `/api/clients/scan` — keep `scanAll`'s signature identical.

## Acceptance criteria

1. `grep -n "classifyAll\|scanAll" backend/src/main/java/com/localfilebrain/Main.java`
   shows them ONLY inside `runStartupReindex` (bulk path), not in the watcher
   hook lambda.
2. `grep -c "sumIndexedTokens" backend/src/main/java/com/localfilebrain/ingestion/IngestionPipeline.java`
   → unchanged (1 call site); new behavior verified by the cache test.
3. All new tests in Step 6 pass.
4. Full backend suite `mvn -B -ntp test` → 0 failures, 0 errors (417+ tests, plus new ones).
5. Behavioral spot-check in the test from Step 6.2: with 30 files and
   `COMMIT_EVERY_FILES = 25`, the run produces exactly 2 commit points (25 + 5)
   — assert indirectly: after run, all 30 searchable; no per-file commit
   regression assertions needed beyond this.
6. UI suite + build still green (no UI changes).
