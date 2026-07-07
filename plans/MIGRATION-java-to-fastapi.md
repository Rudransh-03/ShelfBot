# Rudo — Java → FastAPI Backend Migration Plan

> **📍 Navigation:** [Index](README.md) · [How to execute](EXECUTION-PROTOCOL.md) · [Tracker](TRACKER.md) · Prev: [PLAN-5](PLAN-5-versioned-migrations.md) · Next: [ROADMAP](ROADMAP-rudo-v2.md)
> **Execute when:** Decision **D-1 = GO** ([Decision log](README.md#decision-log)) AND Phase 0 stabilization shipped. Waves execute in order (§13 checklist); each wave follows [EXECUTION-PROTOCOL.md](EXECUTION-PROTOCOL.md) with the wave's rows as the "Files to touch" list; record in [TRACKER.md](TRACKER.md#migration-waves-open-only-after-d-1--go).
> **When D-1 is decided:** update the [Decision log](README.md#decision-log) and flip PLAN-1/2/3/5 rows in the tracker per §14.

**Status:** Proposal (requires an explicit go/no-go decision — see §0)
**Scope:** Parts 1 & 2 of the migration+roadmap request. Part 3/4 (V2 product
roadmap and phased execution) live in [ROADMAP-rudo-v2.md](ROADMAP-rudo-v2.md).
**Source of truth cross-referenced:** `plans/PLAN-1…5`, `plans/README.md`,
`production_report.md`, `rudo-milestone-2-platform-reliability.md`, and the
current code (73 Java main-source files, verified by inventory on 2026-07-07).

---

## 0. Decision brief — read this before committing

An honest engineering assessment, because this migration is a major bet and the
rest of this document only makes sense if the premise holds.

**What Java buys Rudo today** (why the current stack was the right V1 call):
single shaded JAR + jlink runtime (no runtime install for users), pure-Java
Lucene HNSW (no native vector-DB dependency to ship per-OS), Tika (broadest
document-format coverage available anywhere), ONNX Runtime embeddings in-process.
The packaging story is *solved* and battle-tested by Milestone 2's supervisor.

**What FastAPI/Python buys Rudo tomorrow** (why migrating can still be right):
the entire V2 roadmap — agents, MCP servers/clients, LLM tooling, eval
harnesses, CLI — has its center of gravity in Python. Every framework you will
want (MCP SDK, agent runtimes, fastembed, LanceDB, instructor, pytest-based
eval) is Python-first. Two backends (Java core + Python AI layer) would be the
worst outcome: double packaging, double supervision, IPC between them.

**Costs you must accept if you proceed:**
1. **Format coverage regression risk.** Nothing in Python matches Tika 1:1.
   The mitigation matrix is in §5.3, but expect a tail of "this file indexed
   before and doesn't now" reports.
2. **Packaging risk.** PyInstaller bundles are heavier and more fragile than a
   jlink runtime (Gatekeeper quirks, antivirus false positives on Windows).
   Budget real time for it (§12).
3. **A ~3–4 month feature freeze on the backend** while parity is built, or a
   painful long dual-maintenance window.
4. **A full re-verification burden**: 417 JUnit tests encode hard-won behavior
   (date-ambiguity rules, grounding, prompt-injection fencing, reorg safety
   belts). Every one must be ported or consciously retired.

**Recommendation:** migrate — but only as **Phase 1 of the V2 roadmap**, not as
an isolated rewrite, and only after Phase 0 stabilization ships (signing,
proxy URL, observability). If V2 (agents/MCP/CLI/platform) is *not* being
pursued, do **not** migrate; execute PLAN-1…5 on the Java backend instead.
The decision gate and its consequences for PLAN-1…5 are formalized in §14.

---

# PART 1 — The FastAPI target architecture

## 1. Stack

| Concern | Java (today) | FastAPI (target) | Notes |
|---|---|---|---|
| Language/runtime | Java 17, jlink runtime | Python 3.12, bundled via PyInstaller (onedir) | §12 |
| HTTP server | `com.sun.net.httpserver` | FastAPI + uvicorn (single worker) | localhost-only bind, same port handshake |
| Streaming | hand-rolled SSE | `sse-starlette` (or `StreamingResponse`) | identical event names: `token`, `done`, `error` |
| Metadata DB | SQLite via sqlite-jdbc, 1 synchronized connection | SQLite via SQLAlchemy 2.0 + aiosqlite | **schema unchanged** — same DB files open in place |
| Migrations | none (PLAN-5 proposes `user_version`) | Alembic | absorbs PLAN-5; see §6 + §14 |
| Vector store | Lucene 9 HNSW (custom high-dim codec) | **LanceDB** (embedded, disk-based ANN) | contract preserved: cosine distance in [0,2]; §5.2 |
| Embeddings | ONNX Runtime + DJL tokenizer, `bge-small-en-v1.5` | **fastembed** (`BAAI/bge-small-en-v1.5`, ONNX) | same model ⇒ exported vectors stay valid |
| Text extraction | Apache Tika + Tesseract shell-out | pymupdf, python-docx, openpyxl, python-pptx, striprtf, BeautifulSoup, pytesseract | §5.3 risk matrix |
| File watching | JDK WatchService (polling on macOS) | **watchfiles** (Rust; FSEvents/inotify native) | fixes audit risk #9 for free |
| LLM client | `HttpURLConnection` hand-rolled | httpx (async, streaming) | prompts ported **verbatim** |
| Background jobs | 3 ad-hoc executors (+PLAN-3 JobManager) | `jobs/manager.py` on asyncio.Task + ProcessPool for CPU work | native cancellation replaces cooperative flags |
| Config | `config.properties` + env | pydantic-settings; reads the SAME `config.properties` for back-compat | §7 |
| Logging | Logback rolling file | stdlib logging + RotatingFileHandler, same `logs/` dir | + PLAN-4 ring buffer/metrics ported |
| Tests | JUnit 5 (417) | pytest + pytest-asyncio + httpx test client | + a Java↔Python parity harness (§11) |

Pinned early decisions (do not relitigate mid-migration): LanceDB over
hnswlib/Chroma/sqlite-vec (embedded, no server, disk-based, columnar filters
give us the per-client path pre-filter Lucene's `TermInSetQuery` provides
today); SQLAlchemy over raw sqlite3 (Alembic integration, typed repos);
uvicorn single-worker (desktop app — one process, supervised by Electron).

## 2. Folder structure (FastAPI best practice, adapted for a desktop backend)

```
backend-py/
├── pyproject.toml                  # uv/pip; pinned deps
├── alembic/                        # versioned DB migrations (absorbs PLAN-5)
│   └── versions/
├── src/rudo/
│   ├── main.py                     # entrypoint: arg parse (--server --port), uvicorn,
│   │                               #   prints SHELFBOT_SERVER_READY:<port> (contract w/ supervisor.js)
│   ├── config.py                   # Settings (pydantic-settings) ← AppConfig.java
│   ├── paths.py                    # UserDataPaths ← config/UserDataPaths.java (same dirs!)
│   ├── data_migrator.py            # ← config/DataMigrator.java (same marker files)
│   ├── api/
│   │   ├── deps.py                 # X-Shelfbot-Token dependency, CORS, stores DI
│   │   ├── errors.py               # exception handlers → {"error": msg} (same shape)
│   │   └── routes/
│   │       ├── health.py status.py auth.py config.py
│   │       ├── index.py query.py conversations.py files.py
│   │       ├── extract.py deadlines.py timeline.py attention.py
│   │       ├── clients.py reorg.py jobs.py diagnostics.py
│   ├── core/
│   │   ├── logging.py metrics.py ring_log.py     # PLAN-4 ports
│   │   └── security.py                            # constant-time token compare
│   ├── db/
│   │   ├── engine.py models.py                    # SQLAlchemy, EXISTING schema
│   │   └── repos/ (metadata.py chat.py deadlines.py clients_repo.py
│   │              reorg_log.py summaries.py dates.py entities.py)
│   ├── vector/store.py                            # ← storage/VectorStore.java (LanceDB)
│   ├── embedding/ (base.py local_fastembed.py openai.py ollama.py factory.py)
│   ├── ingestion/ (scanner.py extractor.py chunker.py pipeline.py watcher.py
│   │              pdf_pages.py doc_classifier.py)
│   ├── query/ (engine.py history.py prompts.py routing.py sanitizer.py)
│   ├── llm/client.py                              # ← llm/GPT4oMiniClient.java
│   ├── extract/ (engine.py service.py fields.py currency.py)
│   ├── deadline/ (scan.py engine.py prefilter.py maintenance.py missing.py)
│   ├── timeline/date_scanner.py
│   ├── clients/ (matcher.py resolver.py suggester.py entity_scanner.py membership.py)
│   ├── reorg/ (analyzer.py clustering.py plan_builder.py tool_loop.py prompts.py
│   │          executor.py undo.py scope_guard.py llm_client.py models.py)
│   ├── summarize/engine.py
│   ├── attention/builder.py
│   ├── chatstore/                                 # (folded into db/repos/chat.py — listed for mapping)
│   ├── jobs/manager.py                            # PLAN-3 port on asyncio
│   └── util/ (hashing.py pathnorm.py templates.py)
└── tests/
    ├── unit/ …mirrors src…
    ├── contract/                                  # HTTP response-shape tests (frozen JSON fixtures)
    └── parity/                                    # Java↔Python golden-corpus diff harness
```

## 3. Non-negotiable compatibility contracts

These are what make the migration invisible to users and to the Electron layer.
Freeze them in `tests/contract/` before writing feature code.

1. **Process contract:** launched with `--server --port N`; prints
   `SHELFBOT_SERVER_READY:<port>` to stdout when listening; honors env vars
   `SHELFBOT_LOCAL_TOKEN`, `SHELFBOT_PROXY_URL`, `SHELFBOT_DATA_DIR`,
   `SHELFBOT_LEGACY_DIR`; exits cleanly on SIGTERM (supervisor.js contract).
2. **HTTP contract:** every route, method, status code, and response field name
   currently served by `ApiServer.java` (21 registered contexts) — the renderer
   (`ui/src/renderer/src/api/client.js`) is the consumer spec. Additive fields
   allowed; renames forbidden.
3. **Data contract:** same data root (`UserDataPaths`), same SQLite files and
   schemas (`shelfbot-metadata.db`, `shelfbot-chats.db`) opened **in place** —
   users keep chats, deadlines, clients, summaries, reorg undo history with
   zero migration. Only the vector index changes format (§5.2 export path).
4. **Behavioral invariants** (the accuracy work — port verbatim, tests first):
   prompt texts; `ExtractedValue` statuses `OK/AMBIGUOUS/UNVERIFIED/MISSING` +
   `evidence`; date-ambiguity rule (both components ≤12, never reorder);
   currency `CurrencyDescriptor` formatting (INDIAN/WESTERN grouping);
   grounding rules (evidence-substring + digit-run match); JSON-retry (3
   attempts); batch bounds (3 docs, 16k chars/doc); nonce-fenced untrusted
   regions; the exact refusal sentence; "never fabricate — all-MISSING row"
   semantics; `distance = cosine ∈ [0,2]` and `RELEVANCE_THRESHOLD = 1.5` etc.
5. **Auth contract:** per-launch `X-Shelfbot-Token` gate with `/api/health`
   open; proxy-mode JWT forwarding unchanged (the Node proxy is NOT part of
   this migration — see Part 2 conflict C-6).

## 4. API migration

`ApiServer.java` (≈2,500 lines, 21 contexts) splits into 16 route modules.
Mechanical rules:

- One APIRouter per file; register in `main.py` in the same order.
- `deps.py`: `verify_local_token` dependency replicates `preflight()` —
  CORS headers on every response, OPTIONS→204 before the token check,
  `/api/health` exempt, constant-time compare (`hmac.compare_digest`),
  **fail-open when env token unset** (dev parity — flagged as a security item
  to revisit in the roadmap, not silently "fixed" during migration).
- The `register()` last-resort wrapper → FastAPI exception handlers producing
  `{"error": ...}` with the same status mapping (`BadRequestException`→400,
  anything else→500; LLM errors→401/429/502 heuristics in
  `handleFileSummary` reproduced exactly).
- Sub-path routing done by hand today (`/api/conversations/{id}`,
  `/api/deadlines/{id}`, `/api/clients/{id|recompute|assign|…}`) becomes real
  path params — improvement, but preserve trailing-slash tolerance.
- SSE (`/api/query/stream`): same three events; multi-line `data:` splitting
  handled by sse-starlette natively; final `done` payload field-identical.

## 5. Service layer migration

### 5.1 Query engine
`QueryEngine.java` (2,281 lines) → `query/engine.py` + `prompts.py` +
`routing.py`. Architectural improvement baked in (supersedes PLAN-1): the
engine is **stateless**; `ConversationHistory` is a per-request value; requests
in the same conversation serialize on a per-conversation `asyncio.Lock`
(`WeakValueDictionary` keyed by convId), different conversations run
concurrently. All routing paths (small-talk sets, follow-up prefixes, detail
triggers, corpus-overview / inventory / analytics paths with their O(N)
behavior *initially preserved*, optimization later) port 1:1. Amount-math
stays in code (never the LLM) — port `AMOUNT_PATTERN` and the sum/max/min
logic with its tests.

### 5.2 Vector store
`storage/VectorStore.java` → `vector/store.py` on LanceDB. Table columns:
`chunk_id (PK-like), src_path, file_name, chunk_idx, total, text, mime,
mtime, chars, page_start, page_end, vector fixed_size_list[384]`.
Contract methods mirror today's: `upsert`, `replace_by_source_file` (delete
`src_path=?` + add, wrapped in the store's single write lock — LanceDB has no
multi-statement transaction; the lock preserves today's monitor semantics),
`delete_by_source_file`, `delete_all`, `count`, `query(vec, top_k,
allowed_paths)` (pre-filter via `where src_path IN (...)` pushed into the ANN
search — the per-client isolation guarantee), `get_chunks_for_file` (no 1000
cap — fixes the silent-truncation bug from PLAN-2/audit),
`get_chunk_vectors_for_file`, `find_paths_by_file_name`. Distance: LanceDB
returns cosine distance directly when metric="cosine" — keep [0,2] semantics
and add a unit test pinning it.

**Vector data migration (choose B, keep A as fallback):**
- **(B) Lossless export/import:** a ~150-line Java CLI (`ExportChunks.java`,
  added to the Java tree before freeze) that walks the Lucene index and dumps
  JSONL `{chunk fields…, vector: [384 floats]}`; a Python importer loads it
  into LanceDB. No re-embedding, minutes not hours, works offline.
- **(A) Re-index from source files:** already free (local embeddings) and the
  code path exists; acceptable fallback when the export file is missing.
  The startup logic: if LanceDB dir empty AND Lucene dir present → try import;
  else mark metadata drift (`resetMetadataIfDrifted` port) → natural re-index.

### 5.3 Extraction (Tika replacement) — highest-risk area

| Format | Library | Parity risk |
|---|---|---|
| PDF (text) | pymupdf | Low; per-page text also replaces `PdfPageLocator` |
| PDF (scanned) + images | pytesseract (same system Tesseract detection: `tesseract --version` probe port) | Low-medium |
| DOCX/XLSX/PPTX | python-docx / openpyxl / python-pptx | Low |
| Legacy DOC/PPT/XLS | **gap** — textract-style tools are unmaintained | Medium: mark FAILED with a clear reason ("legacy Office format — convert to .docx"), don't silently skip |
| ODT/ODS/ODP | `odfpy` | Low |
| HTML/HTM | BeautifulSoup | Low |
| RTF | striprtf | Low |
| TXT/MD/CSV | stdlib | None |

Rule ported from `TextExtractor`: 10 M-char cap per file, low-text-per-page
heuristic triggers OCR for scanned PDFs, empty-extraction → `EMPTY` outcome.
Acceptance for this module: run the parity harness (§11) over a 200-file mixed
corpus; ≥98% of files that index in Java must index in Python with ≥95% text
similarity (difflib ratio), and every regression must be triaged before cutover.

### 5.4 Ingestion pipeline
`IngestionPipeline.java` → `ingestion/pipeline.py`. Same stages, same budget
constants (`MAX_TOKENS_PER_FILE=500k`, `MAX_TOKENS_TOTAL=200M`), same striped
per-file locking (dict of `asyncio.Lock`), same idempotency (timestamp→hash
short-circuit), same `ProgressListener` events feeding the same `/api/index`
JSON. CPU-bound stages (extraction, OCR, embedding) run in a
`ProcessPoolExecutor` (embedding batches in the worker via fastembed);
PLAN-2's crash-ordering invariant carries over: **metadata INDEXED only after
the vector write is durable**.

### 5.5 Everything else
Deadline scan (budgeted, batched), timeline date scanner (regex rules —
port the test corpus first), doc-type classifier, entity scanner + suggester +
membership engine, summarization engine, attention builder, reorg suite
(analyzer→clustering→LLM tool loop→plan builder→move executor→undo with its
write-ahead undo log) — all pure-logic ports, table in §13. The reorg
`MoveExecutor` safety belts (scope guard, same-volume rename, undo-log-before-
move) are load-bearing: port their tests before the code.

## 6. Database layer migration

- SQLAlchemy models declared to match the **existing** DDL exactly (table
  names, column names, defaults). Verified by opening a copy of a real user DB
  in CI fixtures.
- Alembic baseline revision = current schema; `alembic_version` table is
  additive (does not disturb Java). If PLAN-5 shipped first, its
  `PRAGMA user_version=1` is read and honored: version 1 == Alembic baseline.
- Keep WAL + `synchronous=NORMAL` pragmas on connect (event listener).
- The single-connection `synchronized` model becomes: aiosqlite with a single
  writer connection guarded by an `asyncio.Lock` per DB (SQLite still wants one
  writer) + read connections as needed. Do not introduce a server DB.
- Port PLAN-2's cached token-sum into the metadata repo.

## 7. Configuration management

`config.py` (pydantic-settings), resolution order preserved: env override →
`<dataDir>/config.properties` → legacy CWD `config.properties` (read-only) →
defaults. Same keys (`files.root.paths`, `chunk.size.chars`, …) parsed from
java-properties format (tiny parser, ~20 lines — do NOT switch users to TOML
mid-migration; that's a V2 nicety). `POST /api/config` keeps writing
properties format. Improvement: settings object is immutable per process;
"reload" constructs a new one (removes the mutable `volatile config` pattern).

## 8. Background jobs & async processing

`jobs/manager.py` implements PLAN-3's design natively: `Job` dataclass
(id/type/state/progress/error), per-type single-flight, `asyncio.Task` with
real cancellation (`task.cancel()` + CancelledError handling at batch
boundaries so partial results persist), bounded history, `GET /api/jobs`,
`DELETE /api/index`. The legacy status shapes on `/api/index`,
`/api/deadlines/scan`, `/api/extract` are produced from job state.

## 9. AuthN/AuthZ

`AuthTokenStore` → a tiny in-memory token holder behind `POST/GET/DELETE
/api/auth`; outbound proxy calls attach `Authorization: Bearer <jwt>` via a
shared httpx client. No change to the Node proxy or the Electron sign-in flow.

## 10. Logging, monitoring, observability, error handling

Direct port of PLAN-4 (do PLAN-4's Electron half regardless of migration):
rotating file log to `<dataDir>/logs/rudo-backend.log` (same name/rotation
budget), WARN+ ring buffer (logging.Handler), `Metrics` counters/timers,
`GET /api/diagnostics`, `POST /api/diagnostics/export` (zip, same redaction
rules, never DBs/index/chat content). Error handling: exception-handler
middleware guaranteeing a JSON error body (the "never a bare 500" rule).

## 11. Testing strategy

1. **Port tests before code, module by module.** The 417 JUnit tests are the
   spec. Target: every test class in §13 marked `Port-tests` lands as a pytest
   module in the same PR as its implementation.
2. **Contract tests:** frozen JSON fixtures for all 21 endpoints, captured from
   the running Java backend (a one-time capture script), replayed against
   FastAPI. Field-set equality (allow additive).
3. **Parity harness (`tests/parity/`):** run an identical corpus + scripted
   question set through both backends; diff extraction rows, retrieval source
   sets, deadline items, timeline dates. Gate cutover on the §5.3 thresholds
   plus zero diffs on the pure-logic modules.
4. **The existing LLM-live tests** (`*LiveLlmTest`, skipped in CI) get pytest
   equivalents behind a marker.

## 12. Deployment strategy

- Build: PyInstaller **onedir** (not onefile — startup speed, AV false-positive
  reduction) per OS; output `resources/backend/rudo-backend(.exe)`.
- `ui/src/main/index.js` changes (small, contained):
  `getJavaBin()/getJarPath()` → `getBackendBin()`; drop `-Xmx512m`/`-D` JVM
  args (log dir passed via env `RUDO_LOG_DIR`); `killOrphanedJava()` pgrep
  pattern → `rudo-backend`; **`supervisor.js` needs zero changes** (it already
  takes an injected `launch`).
- Rollout: ship one release with **dual binaries** and an env/setting toggle
  (`RUDO_BACKEND=python|java`), default java; beta cohort flips to python;
  next release defaults python and drops the JAR; keep the Java exporter tool
  one release longer.
- CI: add a python job (ruff + mypy + pytest) alongside the existing mvn job
  until the JAR is dropped; PyInstaller smoke (spawn, wait for READY line,
  curl /api/health) on mac + windows runners.

## 13. File-by-file migration map (all 73 Java files)

Legend — **P** port (rewrite in Python, same behavior, tests first),
**M** merge into another module, **R** replace with library, **D** drop,
**K** keep in Java (transitional tooling). "Tests" = port the matching JUnit class(es).

| Java file | Disp. | Destination | Required changes / notes |
|---|---|---|---|
| `Main.java` | M | `main.py` | Keep: server mode, startup rescan thread→task, embedding-change reset, drift reset, dedup-case-paths, deadline maintenance call. **Drop the interactive CLI menu** (superseded by V2 CLI). |
| `api/ApiServer.java` | M | `api/routes/*` (16 files) + `api/deps.py` + `api/errors.py` | §4. Split by route; extract `resolveScope`/clarify logic into `query/routing.py`; `healMovedFile` moves to `reorg/executor.py` post-move hook; `jitIndexLooseFiles` to `reorg/analyzer.py`. |
| `attention/AttentionBuilder.java` | P | `attention/builder.py` | Pure logic. Tests: `AttentionBuilderTest`. |
| `auth/AuthTokenStore.java` | P | `api/deps.py` (TokenHolder) | 20 lines. |
| `chat/ChatStore.java` | P | `db/repos/chat.py` | Same schema incl. FTS-less search (`searchWithSnippets` LIKE logic 1:1). |
| `client/ClientMatcher.java` | P | `clients/matcher.py` | Word-boundary matching — port regex + tests exactly. |
| `client/ClientResolver.java` | P | `clients/resolver.py` | Clarify/NONE/SCOPED decision — tests are the spec. |
| `client/EntitySuggester.java` | P | `clients/suggester.py` | Pure logic. |
| `client/LocalEntityScanner.java` | P | `clients/entity_scanner.py` | GSTIN/PAN regexes verbatim; add `scan_one` (PLAN-2 carry-over). |
| `client/MembershipEngine.java` | P | `clients/membership.py` | `recompute_all` becomes a Job (PLAN-3 carry-over); keep pinned-wins rule. |
| `config/AppConfig.java` | M | `config.py` | §7. Properties parser; same defaults incl. supported-extensions list. |
| `config/UserDataPaths.java` | P | `paths.py` | **Same directories** — this is the data-continuity linchpin. |
| `config/DataMigrator.java` | P | `data_migrator.py` | Same `.rudo-migrated` marker + staging semantics; most users already migrated — keep for stragglers. |
| `deadline/DeadlineExtractionEngine.java` | P | `deadline/engine.py` | Batched JSON extraction; same fencing; tests. |
| `deadline/DeadlineMaintenance.java` | P | `deadline/maintenance.py` | Purge/roll logic + tests. |
| `deadline/DeadlinePrefilter.java` | P | `deadline/prefilter.py` | Date-bearing heuristics + tests. |
| `deadline/DeadlineScanService.java` | P | `deadline/scan.py` | Daily budget pacing; becomes a Job. |
| `deadline/ExtractedDeadline.java` | P | `deadline/models.py` (pydantic) | DTO. |
| `deadline/MissingDocumentDetector.java` | P | `deadline/missing.py` | Series-gap logic + tests. |
| `embedding/EmbeddingClient.java` | P | `embedding/base.py` (Protocol) | `embed_batch`, `dimensions`, `model_id`. |
| `embedding/EmbeddingClientFactory.java` | P | `embedding/factory.py` | Same provider selection incl. local→OpenAI fallback on load failure. |
| `embedding/LocalEmbeddingClient.java` | R | `embedding/local_fastembed.py` | fastembed, same model id string `local:bge-small-en-v1.5` (keeps the embedding-change wipe from firing). Model download handled by fastembed cache — point it at `~/.shelfbot/models` equivalent or accept new cache dir + keep marker id stable. |
| `embedding/OllamaEmbeddingClient.java` | P | `embedding/ollama.py` | httpx; low priority. |
| `embedding/OpenAIEmbeddingClient.java` | P | `embedding/openai.py` | httpx; proxy path preserved. |
| `extract/CurrencyDescriptor.java` | P | `extract/currency.py` | INDIAN/WESTERN grouping — tests verbatim. |
| `extract/ExtractedValue.java` | P | `extract/fields.py` | Statuses + evidence + `isFlagged` — invariant §3.4. |
| `extract/ExtractionField.java` / `FieldType.java` / `ExtractionOptions.java` / `ExtractionRow.java` | P | `extract/fields.py` | Pydantic models; lenient `FieldType.from`. |
| `extract/ExtractionService.java` | P | `extract/service.py` | Batch bounds (3/16k/280k), cancellation at batch boundary, all-MISSING rows. |
| `extract/StructuredExtractionEngine.java` | P | `extract/engine.py` | Prompts verbatim; retry×3; grounding; tolerant JSON isolation. Tests: full class (21). |
| `ingestion/DocumentTypeClassifier.java` | P | `ingestion/doc_classifier.py` | + `classify_one`. |
| `ingestion/FileScanner.java` | P | `ingestion/scanner.py` | SKIP_DIRECTORIES set, unchanged-detection; tests. |
| `ingestion/FileWatcher.java` | R | `ingestion/watcher.py` (watchfiles) | Keep 1.5 s debounce + exists-at-fire dispatch; drop manual recursion/OVERFLOW handling (library-provided). Architectural improvement over Java. |
| `ingestion/IndexMetadataStore.java` | M | `db/models.py` + `db/repos/*` (6 repos) | 1,890 lines split by domain; schema untouched; token-sum cache added. |
| `ingestion/IngestionPipeline.java` | P | `ingestion/pipeline.py` | §5.4. |
| `ingestion/PageLocator.java` / `PdfPageLocator.java` | M | `ingestion/pdf_pages.py` | pymupdf gives page text natively — simpler. Tests: `PdfPageLocatorTest`. |
| `ingestion/TextChunker.java` | P | `ingestion/chunker.py` | Same size/overlap semantics; tests verbatim (incl. the config-driven ones). |
| `ingestion/TextExtractor.java` | R | `ingestion/extractor.py` | §5.3 matrix; OCR probe port. |
| `llm/GPT4oMiniClient.java` | P | `llm/client.py` | httpx async + streaming; both system prompts verbatim; same timeout values; `oneShot(…, temperature)` variants. |
| `llm/OllamaLLMClient.java` | P | `llm/ollama.py` | Low priority. |
| `model/DocumentChunk.java` / `FileRecord.java` / `IngestionResult.java` | P | `ingestion/models.py`, `db/models.py` | Pydantic/dataclass DTOs. |
| `query/ConversationHistory.java` | P | `query/history.py` | Per-request value object (PLAN-1 absorbed). |
| `query/QueryEngine.java` | M | `query/engine.py` + `routing.py` + `prompts.py` | §5.1. |
| `reorg/ClusteringEngine.java` | P | `reorg/clustering.py` | numpy for cosine/mean-pool; same thresholds; tests (`ClusteringEngineTest`). |
| `reorg/DirectoryAnalyzer.java` | P | `reorg/analyzer.py` | + jit-index hook. |
| `reorg/DirectoryReorgPlan.java` / `ReorgProposal.java` / `ReorgExecutionResult.java` / `ReorgToolLoopResult.java` / `ScopeError.java` | P | `reorg/models.py` | DTOs. |
| `reorg/ExtensionFamily.java` / `FilenameTokenizer.java` | P | `reorg/tokenize.py` | Tests exist for both. |
| `reorg/FileVectorService.java` | P | `reorg/file_vectors.py` | Mean-pooled file vectors + SQLite cache table (exists in schema). |
| `reorg/MoveExecutor.java` / `UndoExecutor.java` | P | `reorg/executor.py`, `reorg/undo.py` | **Safety-critical**: undo-log-before-move, collision resolution, created-dir tracking. Tests first. |
| `reorg/ProxyReorgLlmClient.java` / `ReorgLlmClient.java` | P | `reorg/llm_client.py` | httpx; same session budget headers. |
| `reorg/ReorgPlanBuilder.java` | P | `reorg/plan_builder.py` | Largest reorg logic file; its 759-line test class is the spec. |
| `reorg/ReorgToolLoop.java` / `ToolPrompts.java` | P | `reorg/tool_loop.py`, `reorg/prompts.py` | Prompts verbatim. |
| `reorg/ScopeGuard.java` | P | `reorg/scope_guard.py` | Tests. |
| `storage/VectorStore.java` | R | `vector/store.py` (LanceDB) | §5.2. The custom `HighDimKnnVectorsFormat` is Lucene-specific → D. |
| `summarize/SummarizationEngine.java` | P | `summarize/engine.py` | Plan/run split + tests. |
| `timeline/LocalDateScanner.java` | P | `timeline/date_scanner.py` | Regex/date rules + `scan_one`; tests. |
| `util/FileHashUtil.java` | R | `util/hashing.py` (hashlib) | 5 lines. |
| `util/PathNormalizer.java` | P | `util/pathnorm.py` | `Path.resolve()` + case-canonicalization via `os.path.realpath`; macOS case-insensitivity tests. |
| `util/PromptSanitizer.java` | P | `query/sanitizer.py` | Nonce fencing, safeLabel/safePreview — security-relevant, tests verbatim. |
| `util/TemplateFiles.java` | P | `util/templates.py` | Template/sample-file detection used by the query filter. |
| *(new, transitional)* `tools/ExportChunks.java` | K | stays in Java tree | §5.2 vector export; deleted one release after cutover. |

**Checklist mechanics:** copy this table into the tracking issue; a file is
"done" only when (a) implementation merged, (b) its JUnit tests exist in
pytest, (c) contract/parity checks touching it pass. Suggested order of
waves: `util+config+db` → `vector+embedding+ingestion` → `query+llm` →
`extract+deadline+timeline+clients` → `reorg+summarize+attention` → `api glue`
→ packaging.

---

# PART 2 — Wiring everything together (plans/ cross-reference)

Documents reviewed: `PLAN-1…5`, `plans/README.md`, `production_report.md`,
`rudo-milestone-2-platform-reliability.md`, milestone-1 delivery docs
(historical), `LOCAL_SETUP.md`.

## 14. Disposition of PLAN-1…5 under the migration

| Plan | If migration is **approved to start ≤4 weeks out** | If migration is **deferred/rejected** |
|---|---|---|
| PLAN-1 query concurrency | **Do not execute in Java.** Its design is absorbed as §5.1 requirements (stateless engine, per-conversation lock). Mark the plan file "superseded by MIGRATION §5.1". | Execute as written — it's the top UX win. |
| PLAN-2 indexing throughput | **Partially superseded.** Lucene-specific steps (batched commits) die with Lucene. **Carry over as requirements:** crash-ordering invariant (§5.4), token-sum cache (§6), `scan_one/classify_one` incremental scanners (§13 rows), fetch-cap removal (§5.2). | Execute as written. |
| PLAN-3 job framework | **Do not build in Java.** Absorbed as §8 (asyncio JobManager, same endpoints incl. `DELETE /api/index`). | Execute as written. |
| PLAN-4 observability | **Execute now regardless** — split it: the Electron half (renderer/main log capture, export button, IPC) is backend-agnostic and survives the migration untouched; the backend half is ported per §10. Cheapest insurance for the beta period. | Execute as written. |
| PLAN-5 migrations | **Do not build the Java `SchemaMigrator`.** Alembic replaces it (§6). Keep two of its ideas as Alembic policy: backup-before-migrate with WAL checkpoint, and refuse-to-open-newer (check `alembic_version` > known → fail fast with the user-facing message). | Execute as written. |

## 15. Cross-reference matrix — nothing missed, nothing duplicated

| Capability / decision | Source doc | Where it lands in the new architecture |
|---|---|---|
| OS user-data dir + location migration | Milestone 2 | `paths.py`, `data_migrator.py` — same dirs/markers (§3.3) |
| Backend supervisor (restart, health, shutdown) | Milestone 2 | **Unchanged** `supervisor.js`; only `launch()` in `index.js` changes (§12) |
| Per-launch token, CORS posture, fail-open dev mode | audit §5.4 | `api/deps.py` — behavior preserved; hardening deferred to roadmap Phase 2 (recorded, not lost) |
| Extraction accuracy invariants (evidence/grounding/UNVERIFIED/retry/batch-3/16k) | this repo's latest work | §3.4 + `extract/*` rows in §13 |
| Audit top-10 risks | production_report | #1,#2 done (M2) / Phase 0; #3→§5.1; #4→§5.4+watchfiles; #5 proxy — **out of scope here**, roadmap Phase 2; #6→§10; #7 supervisor done, `-Xmx` concern dissolves (no JVM) but Python memory needs watching (§16); #8→§8 jobs; #9→watchfiles+cancel; #10→Alembic |
| Deadline daily budget, series/missing detection | code + milestone docs | `deadline/*` (§13) |
| Reorg undo-log-before-move safety | code | `reorg/executor.py` — tests-first (§13) |
| UI response shapes | all plans' "hard constraint" | `tests/contract/` (§11.2) |

## 16. Conflicts & outdated decisions — and the ruling

- **C-1: PLAN-1/2/3 assume the Java backend.** *Ruling:* decision-gated per
  §14. Update `plans/README.md` with the outcome the day the go/no-go is made.
- **C-2: PLAN-5 `user_version` vs Alembic.** *Ruling:* Alembic wins under
  migration; PLAN-5's backup + refuse-newer semantics are retained as policy.
- **C-3: `-Xmx512m` memory control disappears.** The audit flagged 512 MB as
  risky; Python has no heap cap. *Ruling:* add an RSS watchdog to the
  supervisor's health model later (roadmap), and cap process-pool workers by
  available RAM in `jobs/manager.py`. Recorded as a §12 packaging-phase task.
- **C-4: `killOrphanedJava` pgrep pattern** would kill nothing (or the wrong
  thing) post-migration. *Ruling:* pattern becomes `rudo-backend`; keep the
  stale-lock cleanup only for the transitional dual-binary release (Lucene
  lock), then delete.
- **C-5: Main.java CLI menu vs the V2 CLI.** *Ruling:* drop the menu now
  (nobody ships it); the real CLI is a V2 deliverable (roadmap Phase 5) built
  on the same Python core as a library — one of the strongest arguments *for*
  this migration.
- **C-6: Should the Node proxy also become FastAPI?** *Ruling:* **not in this
  migration.** The proxy is a separately-deployed service with its own risks
  (audit #5). It is absorbed into the Platform API in roadmap Phase 2, where
  it gets Postgres + real rate limiting — folding it in now doubles the blast
  radius of the desktop migration.
- **C-7: fastembed model cache location** differs from `~/.shelfbot/models`.
  *Ruling:* keep the `modelId()` string identical (`local:bge-small-en-v1.5`)
  so `resetIfEmbeddingChanged` does NOT wipe user indexes; accept a one-time
  ~130 MB re-download, or point fastembed's cache dir at the existing folder —
  decide during implementation, test both against the marker logic.
- **C-8: milestone-1 delivery docs** describe superseded code states.
  *Ruling:* they are historical records; add a one-line "historical" banner —
  do not edit their content.

**Final implementation approach (one paragraph):** Ship Phase 0 stabilization
on the Java backend (signing, proxy URL, PLAN-4) while the migration decision
is ratified. On go: freeze Java features, execute this document wave-by-wave
with contract/parity gates, dual-binary beta, cutover, delete Java. PLAN-1/2/3/5
are absorbed as requirements per §14 and their files annotated as superseded.
The V2 roadmap (agents, MCP, CLI, platform) builds exclusively on the Python
core thereafter — see [ROADMAP-rudo-v2.md](ROADMAP-rudo-v2.md).

## 17. Effort estimate (Part 1 total)

Assuming one strong engineer + AI-assisted porting, tests-first discipline:

| Wave | Content | Effort |
|---|---|---|
| 0 | Skeleton, config/paths/db repos, contract-test capture | 1.5–2 wks |
| 1 | Vector store + embeddings + exporter/importer | 1.5–2 wks |
| 2 | Ingestion (extractor matrix, pipeline, watcher) | 2–3 wks |
| 3 | Query engine + LLM client + conversations | 2–2.5 wks |
| 4 | Extract/Bulk-QA + deadlines/timeline/clients/attention | 2 wks |
| 5 | Reorg suite + summaries + jobs + diagnostics | 2 wks |
| 6 | Packaging (PyInstaller × 2 OS), supervisor wiring, dual-run beta | 2–3 wks |
| — | **Total** | **13–16.5 engineer-weeks (~3–4 months elapsed with beta)** |
