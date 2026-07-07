# Execution Tracker — living status board

> **📍 Navigation:** [Index](README.md) · [How to execute](EXECUTION-PROTOCOL.md)
>
> This file is the ONLY place execution status lives. Update it at the END of
> every work session (Protocol Step 9). Never mark a box done without having
> run the plan's acceptance criteria.

**Legend:** `NOT STARTED` · `IN PROGRESS` · `BLOCKED (reason)` · `DONE (date)` · `SUPERSEDED (by what)`

## Status board

| Work item | Doc | Status | Last updated | Notes |
|---|---|---|---|---|
| PLAN-1 Scalable query execution | [PLAN-1](PLAN-1-scalable-query-execution.md) | NOT STARTED | — | Superseded if D-1 = GO (see [Decision log](README.md#decision-log)) |
| PLAN-2 Indexing throughput | [PLAN-2](PLAN-2-indexing-throughput-incremental-metadata.md) | NOT STARTED | — | Partially superseded if D-1 = GO |
| PLAN-3 Background job framework | [PLAN-3](PLAN-3-background-job-framework.md) | NOT STARTED | — | Superseded if D-1 = GO |
| PLAN-4 Observability & diagnostics | [PLAN-4](PLAN-4-observability-diagnostics.md) | NOT STARTED | — | Execute REGARDLESS of D-1 |
| PLAN-5 Versioned migrations | [PLAN-5](PLAN-5-versioned-migrations.md) | NOT STARTED | — | Superseded if D-1 = GO (Alembic) |
| MIGRATION Wave 0 (skeleton+contracts) | [MIGRATION §13](MIGRATION-java-to-fastapi.md#13-file-by-file-migration-map-all-73-java-files) | BLOCKED (D-1 not decided) | — | |
| MIGRATION Waves 1–6 | [MIGRATION](MIGRATION-java-to-fastapi.md) | BLOCKED (D-1) | — | |
| Phase 0: signing + notarization | [ROADMAP Phase 0](ROADMAP-rudo-v2.md) | NOT STARTED | — | Ops-heavy; needs dev accounts |
| Phase 0: deploy proxy + set PROD_PROXY_URL | [ROADMAP Phase 0](ROADMAP-rudo-v2.md) | NOT STARTED | — | `ui/src/main/index.js:30` TODO |

## Completed history (do not re-do)

| Work item | Evidence |
|---|---|
| Production audit | `production_report.md` (repo root) |
| Persistent User Data Layer (M2 F1) | `UserDataPaths.java`, `DataMigrator.java`, `rudo-milestone-2-platform-reliability.md` |
| Backend Process Supervisor (M2 F2) | `ui/src/main/supervisor.js` + 9 tests in `ui/test/supervisor.test.mjs` |
| Extraction accuracy 5-pack (batch 3, grounding, 16k context, JSON retry, evidence) | `extract/` package + `StructuredExtractionEngineTest` (21 tests) |

## Per-plan acceptance checklists

Tick a box ONLY after running that exact check. Copy the failing output into
Notes if a box can't be ticked.

### PLAN-1
- [ ] `grep -n "queryLock" backend/src/main/java/com/localfilebrain/api/ApiServer.java` → no matches
- [ ] `grep -n "resetHistory()" .../ApiServer.java` → no matches
- [ ] 4 `ConversationLocksTest` tests pass
- [ ] Full backend suite 0 failures · [ ] UI suite + build green

### PLAN-2
- [ ] Watcher hook uses `classifyOne/scanOne` only (grep check in plan §Acceptance-1)
- [ ] VectorStore batch-commit tests pass (3) · [ ] Pipeline crash-ordering test passes
- [ ] Scanner `…One` tests pass · [ ] Token-cache test passes
- [ ] Full backend suite 0 failures · [ ] UI suite + build green

### PLAN-3
- [ ] No `newSingleThreadExecutor` left in ApiServer (grep)
- [ ] `DELETE /api/index` cancels a run (test) · [ ] `GET /api/jobs` works
- [ ] JobManagerTest all pass · [ ] Legacy response shapes unchanged (manual/GET check)
- [ ] Full backend suite 0 failures · [ ] `client.js` has `cancelIndex()` · [ ] UI build green

### PLAN-4
- [ ] `/api/diagnostics` returns app/stores/memory/metrics/recentErrors
- [ ] Export zip contains diagnostics.json + logs, config redacted, no DBs
- [ ] MetricsTest / RingLogAppenderTest / DiagnosticsBundleTest pass
- [ ] Renderer error reaches `<dataDir>/logs/rudo-ui.log` (manual)
- [ ] Settings has working "Export diagnostics" (manual) · [ ] Suites green

### PLAN-5
- [ ] 8+ SchemaMigratorTest tests pass (incl. refuse-newer, rollback, backup-prune)
- [ ] Fresh store constructs with `PRAGMA user_version == 1` (pinned test)
- [ ] `grep user_version backend/src/main/java` → only in SchemaMigrator
- [ ] Full backend suite 0 failures

### MIGRATION waves (open only after D-1 = GO)
- [ ] W0 skeleton + contract fixtures captured from Java backend
- [ ] W1 vector store + embeddings + export/import proven on a real index
- [ ] W2 ingestion parity ≥98% index rate / ≥95% text similarity on 200-file corpus
- [ ] W3 query engine + SSE contract green
- [ ] W4 extract/deadlines/clients/attention parity green
- [ ] W5 reorg + summaries + jobs + diagnostics
- [ ] W6 PyInstaller both OSes; dual-binary beta; cutover
