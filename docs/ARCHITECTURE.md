# Rudo (ShelfBot) — Engineering Map

Dense context doc for fast ramp-up in a new session. **Read this first**, then jump
straight to the named files. Complements `README.md` (product) and `SETUP.md`
(local setup). Paths are relative to repo root. Pair with `CLAUDE.md` + the
auto-memory index.

## 1. What it is
Local-first **RAG desktop app**: indexes a user's files, answers questions about
them (chat with citations), organizes them, and surfaces deadlines. Privacy
positioning; target **$4/mo**, run-cost ceiling **$1.5/user**.

Three processes:
- **backend/** — Java HTTP server, **port 9876**. The brain: indexing, retrieval/RAG,
  chat, deadlines, reorg. Runs as a shaded JAR (`backend/target/local-file-brain-1.0.0.jar`), **not from source**.
- **ui/** — Electron + React renderer (desktop app). Spawns the backend JAR.
- **proxy/** — Node server, **port 8787**: auth'd OpenAI passthrough + licensing/usage,
  SQLite keyed by `device_id` (no accounts). **Stores no chat content.** OpenAI key lives only here (`.env`).

## 2. Run / build / test
- **Run app:** `cd ui && npm run dev` (spawns backend JAR with `--server --port 9876`, then Electron).
- **After ANY Java change, rebuild:** `cd backend && mvn package -DskipTests` (UI runs the JAR, so unbuilt Java = stale).
- UI (JS/JSX/CSS) **hot-reloads** under `npm run dev`. Renderer build check: `cd ui && npm run build`.
- Backend tests: `cd backend && mvn test`.

## 3. Data stores & models
- **Lucene** vector index: `backend/shelfbot-vector-index/` (dev; relative to CWD). **Single-writer lock** — only one backend per index.
- **SQLite** (dev, in `backend/`, gitignored): `shelfbot-metadata.db` (file index: `file_index` table, `status` = INDEXED|FAILED|IN_PROGRESS, `error_message`, chunk/token counts, clients, deadlines), `shelfbot-chats.db` (chat threads + messages).
- **Embeddings: local** `bge-small-en-v1.5` (384-dim, ONNX) by default → **free, on-device**. Model cached in `~/.shelfbot/models/`. (`embedding.provider`, default `local`.)
- **LLM:** `gpt-4o-mini` class via proxy (default `api.mode=proxy`) or direct (`api.mode=direct` + `openai.api.key`). Config: `backend/config.properties` (gitignored) → `AppConfig.java`.
- **Cost:** indexing/embeddings = local/free. OpenAI only at: chat query, deadline scan, file summaries, reorg decisions. Quotas enforced at proxy.

## 4. Backend map (`backend/src/main/java/com/localfilebrain/`)
- **Main.java** — entry. CLI menu by default; `--server --port N` runs the HTTP server (this is how UI launches it).
- **api/ApiServer.java** — all HTTP routes (~2000 lines). Handlers: status, index, query(+stream), config, files, files/summary, conversations, deadlines, missing, clients, reorg, auth. Holds in-flight indexing state (`currentProgress`, `fileStatuses`/activeFiles). `queryParam(ex,key)` helper.
- **config/AppConfig.java** — `config.properties` loader. `getApiMode`/`getProxyUrl`/`getOpenAiApiKey`/`getVectorIndexPath`/`getEmbeddingModelPath`. `getOrDefault` reads the file only (NOT system props/env, except `require()` `${ENV}`).
- **auth/AuthTokenStore.java** — in-memory JWT (set at runtime by Electron's device bootstrap via `/api/auth`; absent when backend run standalone → proxy LLM calls 401).

### Ingestion (`ingestion/`)
`FileScanner` (walk roots) → `TextExtractor` (Tika; OCR via Tesseract if installed) → `TextChunker` (~1800 chars, 200 overlap) → embed (local) → `ChunkWriter`/`VectorStore` (Lucene) + `IndexMetadataStore` (SQLite).
- **IngestionPipeline.java** — orchestrates. **Parallel across files** (`threads = min(files, max(2, cores/2))`); embedding serialized via `embedLock`. Progress via `ProgressListener`: `onProgress(processed,total,failed,currentFile)` (fires per-file-completion; `currentFile` is always null), `onFileStart/onFileStage(stage,done,total)/onFileEnd`. Stages: `extracting|chunking|embedding|saving`. `recordFailed()` → `markFailed` (status FAILED + error_message).
- **FileWatcher.java** — live re-index of changed files in roots.
- **IndexMetadataStore.java** — SQLite DAO. `listIndexedFilesBySizeDesc()`, `listFailedFiles()`, `markFailed`, counts, clients, deadlines. `mapRow` → `FileRecord`.
- **PdfPageLocator.java** — maps chunks → PDF page numbers (additive; chunk text unchanged, retrieval unaffected). Powers page citations.

### Retrieval / RAG (`query/`, `llm/`, `storage/`)
- **query/QueryEngine.java** — the RAG pipeline. Flow: resolve client scope → KNN search (TOP_K=40) → relevance-threshold + relative-distance filter → diversify per file (`MAX_CHUNKS_PER_FILE=4`, `MAX_CONTEXT_CHUNKS`) → full-file expansion for top-N files → template filter → LLM answer (stream/non-stream) → `groupMatchesByFile` + **`trimSourcesToCited`** (chips = files named in answer; **empty for clarifying questions** via `isClarifyingQuestion`). `detectFileScope` honors a bare filename if it resolves to exactly one in-scope file.
- **llm/GPT4oMiniClient.java** — OpenAI calls (proxy or direct). **`SYSTEM_PROMPT`** (answer rules incl. "FIRST gauge intent → clarify vague one-word queries"; ABOUT vs LIST; FOCUSED; refusal last-resort) + `FOLLOW_UP_SYSTEM_PROMPT`. **`buildUserMessage`** labels excerpt blocks `=== <filename> ===` (NO "Source N" — that leaked into answers).
- **query/ConversationHistory.java** — per-thread history fed to the LLM.
- **storage/VectorStore.java** — Lucene KNN + per-file chunk fetch; client path-filter for isolation.

### Per-client isolation (`client/`)
Dormant unless clients registered. `ClientResolver` (NONE|SCOPED|CLARIFY — never guesses; clarifies on ambiguity), `ClientMatcher` (match client identifiers in question), `MembershipEngine` (auto-tag a file to a client only on EXACTLY ONE identifier match; else unassigned), `EntitySuggester` (detect candidate clients from docs → suggestions). Scope tokens: `__all__` (unscoped), `__unassigned__` (personal). Clarify chips offer clients + "Personal / unfiled".

### Deadlines (`deadline/`) — Pro feature (dev-unlocked in UI)
`DeadlinePrefilter` (cheap local prefilter) → `DeadlineExtractionEngine` (batched LLM) → `ExtractedDeadline`; `DeadlineScanService` (incremental, budget-capped, quota-aware; auto-runs after indexing). `MissingDocumentDetector` (local gap detection in recurring series → `/api/missing`). `DeadlineMaintenance` (clears past items). Reminders → `.ics` via Electron `ics.js`.

### Other backend
- **reorg/** — folder tidy. `ScopeGuard`/`ScopeError` (refuse too-broad targets), `DirectoryAnalyzer`+`ClusteringEngine`+`FileVectorService`, `ReorgToolLoop` (LLM tool loop) → `ReorgProposal`; `MoveExecutor`/`UndoExecutor` (batchId-based undo). Routes `/api/reorg/{preview,execute,undo,history}`.
- **summarize/SummarizationEngine.java** — one-page file brief (cached); `/api/files/summary`.
- **chat/ChatStore.java** — thread persistence + full-text search-with-snippets.

## 5. Backend API (one-liners)
`GET /api/health` · `GET /api/status` (indexedFiles, failedFiles, totalChunks, lastIndexed, rootPaths, embeddingModel, ocrAvailable, accessIssues, platform) · `POST /api/index` (start) `GET /api/index` (poll: running, progress{processed,total,failed}, activeFiles[{name,stage,done,total,path}], result) · `POST /api/query` · `POST /api/query/stream` (SSE: `token`/`done`/`error`; done = {answer?,sources,found,conversationId,clarify?,scope?}) · `GET/POST /api/config` · `GET /api/files` (`?status=failed` → {name,path,reason}) `DELETE /api/files` · `POST /api/files/summary` · `GET /api/conversations` (`?q=` search) `POST` create · `GET/POST(rename)/DELETE /api/conversations/{id}` · `GET/POST /api/deadlines/scan` · `GET /api/deadlines?status=` `POST/DELETE /api/deadlines/{id}` · `GET /api/missing` · `GET/POST /api/clients` `POST/DELETE /api/clients/{id}` `POST /api/clients/{recompute,assign,accept,dismiss}` `GET /api/clients/suggestions` · `POST /api/reorg/{preview,execute,undo}` `GET /api/reorg/history` · `POST /api/auth` (set token).

## 6. Frontend map (`ui/src/renderer/src/`)
- **App.jsx** — shell: TitleBar, Sidebar, content (Chat/Library/Deadlines/Organize/Settings via `view`), global modals (Welcome, ClientSuggestion, **DeadlineReview**), Toast, UpdateBanner, SearchModal (⌘K). Passes `onGoLibrary`/`onGoSettings`/`onNavigate` for cross-view nav.
- **context/AppContext.jsx** — central state: `api` client, connected, `stats`, indexing/`progress`/`activeFiles`, `triggerIndex`, deadlines (stats/scanning/progress, `scanDeadlines`), conversations (+CRUD), `auth` (device plan/usage), `autoModalOpen` (shared slot so auto-popups queue, not overlap). Polls /api/status (idle 30s) + /api/index (busy 1.2s) + auto-scans deadlines after index (debounced 8s).
- **api/client.js** — `ApiClient` wrapping all endpoints incl. `queryStream` (SSE parser), `listFailedFiles`.
- **views/**: `Chat.jsx` (streaming, sources chips with page labels, copy/email/CSV-export actions, ⌘F find, clarify chips; **no scope dropdown** — auto-detect), `Library.jsx` (stats grid [Files Indexed + Failed (clickable → FailedFilesModal)], Index Control + **smooth progress** via `stageFraction` + inline active files, IndexedFilesPanel with summaries/delete, IndexStatusModal), `Deadlines.jsx` (scan, Open/Reminders tabs, ReminderModal → `.ics`, missing-docs), `Organize.jsx` (pick→preview→apply→undo state machine), `Settings.jsx` (folders + Save&re-index→Library, ClientsCard, friendly "Status" card, Plan).
- **components/**: Sidebar (nav + chat list + sync/index status), Toast (types **`'s'|'e'|'i'` only**), WelcomeModal (3-step onboarding), ClientSuggestionModal, DeadlineReviewModal, SearchModal, UpdateBanner, Mascot, BackgroundFX, TitleBar.
- **IPC bridge** `window.electron.*` (preload `src/preload/index.js`, handlers `src/main/index.js`): `openPath, openExternal, selectFolder, exportFile, createReminder, deviceBootstrap/deviceMe/deviceLogout, onApiPort, onUpdateStatus/installUpdate, minimize/maximize/closeWindow`. `ics.js` builds calendar events.

## 7. Gotchas
- **Rebuild the JAR** after Java edits, or the app runs stale code.
- **Lucene single-writer lock** — kill orphans: `lsof -ti :9876 | xargs kill`. Run an isolated test backend from a temp CWD (paths are relative).
- **Never wipe** `shelfbot-vector-index/` or `shelfbot-metadata.db` without asking — re-indexing is expensive.
- **Toast types are `'s'/'e'/'i'`** (not `'success'/'error'`).
- Don't surface internal metrics (tokens/chunks/"Source N"/raw paths) to users.

## 8. Headless local-test recipe (no GUI — for verifying changes)
Run the real backend code without disturbing the user's setup:
1. `cp backend/config.properties /private/tmp/T/config.properties`; append `api.mode=direct` (uses the key in that file → LLM works) + `vector.index.path=<abs>` + `metadata.db.path=<abs>` (point at the **real** dirs to reproduce real data, or temp dirs for isolation). Indexing alone needs no key (local embeddings).
2. `cd /private/tmp/T && java -Djava.awt.headless=true -jar <abs JAR> --server --port 9876` (background).
3. `curl --retry-connrefused --retry 90 --retry-delay 1 http://localhost:9876/api/health`, then `curl -X POST .../api/query -d '{"question":"..."}'` or `POST /api/index` + poll `GET /api/index`.
4. **Cleanup:** `lsof -ti :9876 | xargs kill`; `rm -rf /private/tmp/T`. Verify real `config.properties` unchanged. (Chat DB defaults to CWD → stays in temp, no pollution.)

## 9. Conventions
- Commits go **directly to `main`** (solo repo, `origin` = github.com/Rudransh-03/ShelfBot). End commit msgs with the `Co-Authored-By: Claude ...` line. **Only commit/push when asked.**
- **Diagnose before fixing** retrieval bugs (inspect logs/chunks/scores). **Prove with real runs** before "done". No destructive ops without permission. Hide internal metrics from users. Evaluate features from the customer's POV.

## 10. Product direction (priority pillars)
1. Daily **"what needs you today"** brief (Gmail + Calendar + docs).
2. **Compounding personal memory** (moat).
3. **Document-heavy life-admin** (reimbursements, claims, form-filling, renewals).
Proactive features need Rudo to become an always-on tray/launch-at-login app (gating decision, not yet made).

## 11. Recent work (2026-06-14)
Intuitiveness + answer-quality pass: fixed Organize toast types; corrected/removed false onboarding privacy claim; Settings one-click **Save & re-index**→Library; **removed chat scope dropdown** (auto-detect); removed token-budget meter + per-file chunk/token jargon + "Chunks Stored"; **smooth indexing progress** + real/honest per-file status; **clickable Failed-files** modal (`?status=failed`); fixed **"Source N" leak** (filename-only labels) + **vague-query clarifier**; clarify turns show **no source chips**; **queued auto pop-ups** (`autoModalOpen`); **post-index DeadlineReviewModal**; friendly Settings "Status" card; long-answer Email = copy-to-clipboard. All verified via headless runs.
