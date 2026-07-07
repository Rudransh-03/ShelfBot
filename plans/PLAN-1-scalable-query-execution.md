# PLAN 1 — Scalable Query Execution (remove the global chat lock)

> **📍 Navigation:** [Index](README.md) · [How to execute](EXECUTION-PROTOCOL.md) · [Tracker](TRACKER.md) · Next: [PLAN-2](PLAN-2-indexing-throughput-incremental-metadata.md)
> **Execute when:** Decision **D-1 ≠ GO** (see [Decision log](README.md#decision-log)). If D-1 = GO this plan is SUPERSEDED by [MIGRATION §5.1](MIGRATION-java-to-fastapi.md#51-query-engine) — do not execute it in Java.
> **Executor:** follow [EXECUTION-PROTOCOL.md](EXECUTION-PROTOCOL.md) step by step; record results in [TRACKER.md](TRACKER.md#plan-1).

## Goal

Two concurrent chat requests must never block each other. Today
`ApiServer.answerInConversation()` wraps the ENTIRE rehydrate → retrieve →
LLM-call → persist sequence in one global monitor (`queryLock`,
`ApiServer.java:107`), and the LLM read timeout is 120 s
(`GPT4oMiniClient.java`, `setReadTimeout(120_000)`). One slow upstream call
freezes every other chat request. Root cause: a single shared `QueryEngine`
whose conversation history is a mutable field swapped in per request
(`QueryEngine.history`, `QueryEngine.java:339`).

After this plan:
- Queries in **different** conversations run fully in parallel.
- Queries in the **same** conversation stay serialized (to preserve turn order),
  via a per-conversation lock.
- The CLI mode (`Main.runQueryLoop`) keeps working unchanged.

## Files to touch

1. `backend/src/main/java/com/localfilebrain/query/QueryEngine.java`
2. `backend/src/main/java/com/localfilebrain/api/ApiServer.java`
3. NEW `backend/src/main/java/com/localfilebrain/api/ConversationLocks.java`
4. NEW `backend/src/test/java/com/localfilebrain/api/ConversationLocksTest.java`

Do NOT touch: `GPT4oMiniClient`, `VectorStore`, `ChatStore`, any UI file.
(`GPT4oMiniClient` creates a fresh `HttpURLConnection` per call — already
thread-safe. `VectorStore` reads go through `SearcherManager` — designed for
concurrent readers. `ChatStore` methods are `synchronized` — safe. The local
ONNX embedding session is thread-safe for concurrent `run` calls.)

## Steps, in order

### Step 1 — Thread `ConversationHistory` through QueryEngine as a parameter

In `QueryEngine.java`:

1. Add public overloads that take an explicit history (keep ALL existing
   public methods working exactly as before — they delegate using the engine's
   own field, which the CLI relies on):

   ```java
   public QueryResult query(String question, java.util.Set<String> allowedPaths,
                            ConversationHistory history) {
       try { return queryInternal(question, allowedPaths, history); }
       catch (Exception e) { return safetyNet(question, e, null, history); }
   }

   public QueryResult queryStream(String question, java.util.function.Consumer<String> onToken,
                                  java.util.Set<String> allowedPaths,
                                  ConversationHistory history) {
       try { return queryStreamInternal(question, onToken, allowedPaths, history); }
       catch (Exception e) { return safetyNet(question, e, onToken, history); }
   }
   ```

2. Change the private methods `queryInternal`, `queryStreamInternal`,
   `safetyNet`, and every private helper that currently reads the `history`
   field (`routeByIntent`, `answerCorpusOverview`, `answerInventoryQuery`,
   `answerAnalyticsQuery`, `answerFromFileScope`, and any other method
   containing `history.` — grep for `history.` to find all of them; there are
   uses at approximately lines 462, 472, 553, 585, 592, 686, 1056, 1079,
   1105, 1498) to accept a `ConversationHistory history` parameter and use it
   instead of the field. Mechanical change: add the parameter, pass it down.

3. The old field-based public methods become one-liners:

   ```java
   public QueryResult query(String question, java.util.Set<String> allowedPaths) {
       return query(question, allowedPaths, this.history);
   }
   // same for query(String), queryStream(...) variants
   ```

4. Keep `resetHistory()`, `addHistoryExchange(...)`, `clearHistory()`
   untouched — the CLI uses them. Add a Javadoc note on the `history` field:
   "used only by the CLI / legacy path; the API server passes a per-request
   history explicitly."

### Step 2 — Per-conversation lock striping

Create `backend/src/main/java/com/localfilebrain/api/ConversationLocks.java`:

```java
package com.localfilebrain.api;

/**
 * Striped locks keyed by conversation id. Requests in the same conversation
 * serialize (turn order in ChatStore stays coherent); requests in different
 * conversations run in parallel. Striping bounds memory: same id always maps
 * to the same stripe. Mirrors the striped-lock pattern in IngestionPipeline.
 */
final class ConversationLocks {
    private static final int STRIPES = 64;
    private final Object[] locks = new Object[STRIPES];
    ConversationLocks() { for (int i = 0; i < STRIPES; i++) locks[i] = new Object(); }
    Object lockFor(String conversationId) {
        return locks[Math.floorMod(conversationId == null ? 0 : conversationId.hashCode(), STRIPES)];
    }
}
```

### Step 3 — Rewrite `ApiServer.answerInConversation`

Replace the current body (which does `synchronized (queryLock) { engine.resetHistory(); … }`):

```java
private final ConversationLocks convLocks = new ConversationLocks();

/** Answerer now receives the per-request history alongside the engine. */
private QueryEngine.QueryResult answerInConversation(
        String convId, String question,
        java.util.function.BiFunction<QueryEngine, ConversationHistory, QueryEngine.QueryResult> answerer) {
    synchronized (convLocks.lockFor(convId)) {
        ChatStore cs = chatStore();
        QueryEngine engine = getOrInitQueryEngine();
        ConversationHistory history = new ConversationHistory(20);
        for (ChatStore.Exchange e : cs.getRecentExchanges(convId, 20)) {
            history.add(e.question(), e.answer());
        }
        cs.addMessage(convId, "user", question);
        QueryEngine.QueryResult result = answerer.apply(engine, history);
        cs.addMessage(convId, "assistant", result.answer(), sourcesToJson(result.sourceFiles()));
        return result;
    }
}
```

- Import `com.localfilebrain.query.ConversationHistory`.
- Delete the `queryLock` field (`ApiServer.java:107`). After this change,
  `grep -n queryLock ApiServer.java` must return nothing.
- Update the two call sites:
  - `handleQuery` (~line 489):
    `answerInConversation(convId, question, (e, h) -> e.query(question, sd.allowedPaths, h));`
  - `handleQueryStream` (~line 574):
    `answerInConversation(convId, question, (engine, h) -> engine.queryStream(question, token -> { … }, sd.allowedPaths, h));`
    (keep the existing token-writing lambda body exactly as it is).

### Step 4 — Tests

Create `ConversationLocksTest.java` (package `com.localfilebrain.api`, so it can
see the package-private class):

- `sameIdMapsToSameLock` — `lockFor("abc") == lockFor("abc")`.
- `nullIdDoesNotThrow` — `lockFor(null)` returns a non-null object, twice the same.
- `differentConversationsRunConcurrently` — spawn 2 threads; thread A holds
  `lockFor("conv-A")` and waits on a `CountDownLatch`; thread B must be able to
  enter `lockFor("conv-B-something-that-hashes-to-a-different-stripe")` and
  count down the latch within 2 s. To guarantee different stripes, pick the
  second id in a loop until
  `Math.floorMod(id2.hashCode(),64) != Math.floorMod("conv-A".hashCode(),64)`.
- `sameConversationSerializes` — thread A holds `lockFor("x")`; thread B
  attempting `synchronized(lockFor("x"))` must NOT proceed until A releases
  (assert with a flag + join timeout).

## Edge cases a weaker model would miss

1. **Do not delete the `history` field or `resetHistory`/`addHistoryExchange`**
   — `Main.runQueryLoop` (CLI) and `handleSmallTalk`-driven flows in CLI mode
   depend on the field-based path. Only the API server switches to explicit
   history.
2. **`safetyNet` also touches history** (`history.add` around line 1105 inside
   a try/catch). It must take the history parameter too, or a request that
   throws will compile-fail or write to the shared field.
3. **The clarify path bypasses `answerInConversation`** — `resolveScope` +
   the clarify branch in `handleQuery`/`handleQueryStream` write to `ChatStore`
   directly without the lock. That is fine (ChatStore is internally
   synchronized); do not try to wrap them.
4. **`this.queryEngine = null` on auth/config change** (`handleAuth`,
   `handleConfig`): an in-flight request keeps its local `engine` reference and
   finishes against it. That is pre-existing, self-healing behavior — leave it.
5. **`ConversationHistory` is not thread-safe** — that is fine because each
   request builds its own instance and same-conversation requests serialize on
   the stripe. Do not add `synchronized` to it.
6. **Do not shrink `HISTORY_SIZE` semantics**: the rehydrate loop uses
   `getRecentExchanges(convId, 20)` and `new ConversationHistory(20)` — keep 20
   to match existing behavior exactly.
7. **Streaming**: two parallel SSE streams now interleave on the HTTP executor
   — each has its own `OutputStream`; no shared state. Nothing to do, but do
   not "optimize" by sharing buffers.
8. The `BiFunction` import: use `java.util.function.BiFunction` — do not invent
   a new interface unless generics get unwieldy.

## Acceptance criteria

1. `grep -n "queryLock" backend/src/main/java/com/localfilebrain/api/ApiServer.java`
   → no matches.
2. `grep -n "resetHistory()" backend/src/main/java/com/localfilebrain/api/ApiServer.java`
   → no matches (the API path no longer mutates the shared engine's history).
3. All 4 new `ConversationLocksTest` tests pass.
4. Full backend suite: `mvn -B -ntp test` → **0 failures, 0 errors** (417+ tests).
5. UI suite + build still green (no UI changes expected; run anyway).
6. Manual smoke (optional but recommended): start the app, open two chat
   threads, send a question in each quickly — both answer; neither waits for
   the other to finish streaming.
