# Rudo V2 — Product Roadmap & Phased Execution Plan

> **📍 Navigation:** [Index](README.md) · [Tracker](TRACKER.md) · Prev: [MIGRATION](MIGRATION-java-to-fastapi.md)
> **This is strategy, not an executable plan** — phases become executable plans when they start (write a `PLAN-*.md` per deliverable, following the existing plan format, executed via [EXECUTION-PROTOCOL.md](EXECUTION-PROTOCOL.md)).
> **Open decisions owned by this doc:** D-2, D-3 in the [Decision log](README.md#decision-log). Review quarterly.

**Status:** Strategy proposal (Parts 3 & 4 of the migration+roadmap request).
**Companion:** [MIGRATION-java-to-fastapi.md](MIGRATION-java-to-fastapi.md)
(Parts 1 & 2 — the technical foundation everything below builds on).
**Horizon:** 12–18 months.

---

## 0. Strategic framing — read first

There is a real tension in the brief that must be named before roadmapping:
**Rudo today is a privacy-first document assistant for professionals** (the
prompts, deadline/GSTIN/PAN features, and pricing model all target Indian
CAs/SMB professionals), while the brief asks for an **"AI-native developer
platform."** Pivoting the product *away* from its ICP would discard the
distribution wedge; ignoring the platform ambition would cap the ceiling.

**Recommended strategy — two lanes, one core:**

- **Lane A (revenue wedge, unchanged):** the desktop assistant stays the
  flagship. Professionals don't buy platforms; they buy "my documents answer
  questions and never leave my machine."
- **Lane B (platform ceiling):** the *same Python core* (post-migration) is
  progressively exposed as primitives — MCP server → CLI → retrieval API →
  agents. Developers and enterprises meet Rudo through those primitives.
  "Developer platform" is something Rudo **grows into from below**, not a
  repositioning.

Every phase below states which lane it serves. The single most important
sequencing rule: **nothing in Lane B ships before Phase 0/1 make Lane A
stable and portable** — a platform built on an unshippable desktop app is a
demo, not a product.

---

# PART 3 — Rudo V2 capability design

## 1. AI Agents

### 1.1 Architecture: one runtime, typed tools, no framework worship

Build a thin agent runtime rather than adopting a heavyweight framework:

```
src/rudo/agents/
├── runtime.py        # the loop: plan → tool call → observe → iterate; budgets & tracing
├── tools/registry.py # typed tool registry — THE shared surface (agents, MCP, CLI all consume it)
├── memory.py         # per-agent scratchpad + citations ledger
├── policies.py       # budget caps (LLM calls, wall time), permission gates (fs writes need approval)
└── agents/           # thin definitions: prompt + allowed tools + output schema
```

Key decisions:
- **The tool registry is the platform.** Every capability Rudo already has
  becomes a typed tool once: `search_chunks`, `get_document`, `list_files`,
  `extract_fields`, `list_deadlines`, `summarize_file`, `move_files(dry_run)`,
  `export_csv`. Agents, MCP, and the CLI are three *presentations* of this one
  registry — this is what prevents duplicate implementations.
- **Citations are mandatory.** The extraction work already established
  evidence-grounding (`UNVERIFIED` status); the agent runtime inherits it: any
  factual claim in an agent output carries `{file, quote}` provenance or is
  flagged. This is Rudo's differentiator vs. generic agent products.
- **Budgets before autonomy.** Every run has hard caps (LLM calls, tokens,
  wall time) enforced by the runtime, not the prompt — the reorg tool-loop's
  session-budget pattern generalized.
- **The reorg pipeline is already a proto-agent** (tool loop + proposals +
  human approval + undo). Its "propose → human approves → execute → undoable"
  shape is the template for every write-capable agent.

### 1.2 Agent lineup, in build order

| Agent | What it does | Why this order |
|---|---|---|
| **Document Research** | Multi-step retrieval: decompose question → search per sub-question → synthesize with citations | Pure read-only composition of existing tools; immediate chat upgrade ("deep answer" button) |
| **Report Generation** | Research agent + report templates (already exist in UI) → client-ready PDF with evidence appendix | Monetizable for the ICP (CA monthly client reports) |
| **Knowledge Synthesis** | Cross-document briefs: "everything about client X this quarter," contradiction detection | Builds on research agent + client scoping (exists) |
| **Workflow Automation** | Scheduled/triggered chains: watch folder → classify → extract → append to ledger → notify | Needs the job framework + triggers; first "Rudo works while I sleep" feature |
| **Personal Knowledge** | Long-lived memory over the library: entities, relationships, timelines (seed of the knowledge graph) | After retrieval + synthesis are trusted |
| **Code Understanding** | Index a repo, answer/architecture questions | Lane B only; ships **with the CLI**, not the desktop app |
| **Browser agents** | — | **Not building** in this horizon: security surface, weak ICP fit. Revisit ≥18 mo. |

## 2. MCP (Model Context Protocol)

MCP is Rudo's **cheapest, highest-leverage distribution move**: every Claude /
MCP-host user becomes a potential Rudo user without Rudo building a chat UI
for them.

### 2.1 Rudo as an MCP **server** (build first)

`rudo mcp serve` (stdio + streamable-HTTP) exposing the tool registry:

- Tools: `search_documents`, `ask_documents` (retrieval+citations, no
  synthesis — the host's model synthesizes), `get_document_text`,
  `extract_fields`, `list_deadlines`, `list_files`, `get_timeline`.
- Resources: documents by URI (`rudo://file/<hash>`), per-client scopes.
- **Security posture is the selling point:** local-only by default, per-scope
  allowlists (a host session can be pinned to one client workspace), read-only
  tools by default, write tools (`move_files`) behind explicit config.
- Auth: session token handshake, same pattern as the Electron token.

### 2.2 Rudo as an MCP **client** (build second)

Third-party connectors so Rudo's index can ingest beyond the filesystem:
Google Drive, Gmail attachments, Notion, Slack exports — implemented as MCP
clients where servers exist, native connectors where they don't. Enterprise
connectors (SharePoint, Confluence) belong to Phase 6.
Ingestion contract: every connector yields the same `(source_uri, bytes,
mtime, metadata)` shape into the existing pipeline — connectors never write to
stores directly.

### 2.3 Plugin ecosystem & agent-to-agent

- Custom MCP plugins: a manifest (`rudo-plugin.toml`) that registers external
  MCP servers as tool providers inside Rudo's registry — this **is** the
  plugin architecture from the capability table; don't build a second one.
- Agent-to-agent: defer. In this horizon "A2A" = one Rudo agent invoking
  another as a tool (same process, same budgets). No network A2A protocol
  until there's demonstrated demand.

## 3. CLI

**Design principle:** the CLI is the Python core imported as a library — not a
REST client — so it works headless (CI, servers) with no desktop app running,
sharing config/paths/stores with it (single-writer discipline enforced via a
lock file; if the desktop backend is running, CLI defers to its HTTP API).

```
rudo init [--data-dir]                 # create/point at a knowledge base
rudo index <path…> [--watch]           # index folders/repos (code-aware chunking for repos)
rudo status / rudo files / rudo jobs
rudo chat ["question"] [-i]            # one-shot (pipe-friendly) or REPL
rudo search "query" [--json]           # retrieval only, scriptable
rudo extract --schema fields.yaml <scope> --out csv|json
rudo report --template monthly-client --client X --out pdf
rudo deadlines [--json]
rudo sync <folder>                     # alias of index --watch, daemonized
rudo mcp serve [--stdio|--http]        # §2.1
rudo workflow run <file.yaml>          # §1.2 automation, CI-friendly exit codes
rudo export --format jsonl             # chunks+metadata dump (also the migration exporter's successor)
rudo diagnostics                       # PLAN-4 bundle from the terminal
```

- Stack: `typer` + `rich`; `--json` on every read command (machine-readable
  is the point); exit codes contract for CI (`0` ok, `2` partial, `3` failed).
- CI/CD story: `rudo index . && rudo extract --schema contracts.yaml --out
  json | jq …` in a pipeline; a GitHub Action wrapper later.
- Licensing: CLI reuses the proxy JWT (`rudo login` does the device/PKCE flow
  headlessly or via browser); local-embedding operation works fully offline.

## 4. Enterprise features (Lane A ceiling raise + Lane B credibility)

Sequenced strictly — each row depends on the previous:

1. **Team workspaces** — the existing per-client isolation generalized: a
   workspace = scoped index + members. First multi-user primitive.
2. **Permissions** — workspace-level RBAC (owner/editor/viewer) enforced in
   the retrieval layer (the `allowed_paths` pre-filter already proves the
   pattern: isolation enforced in the index, not the prompt).
3. **Shared retrieval / sync** — a self-hostable or cloud "Rudo Hub": members
   sync selected workspaces; retrieval API serves the team index. This is the
   first genuinely *server-side* Rudo and where Postgres + object storage
   enter (and where the Node proxy is absorbed — MIGRATION C-6).
4. **Retrieval APIs + webhooks** — `POST /v1/search`, `POST /v1/ask`,
   `document.indexed` / `deadline.detected` webhooks.
5. **Knowledge graph & shared memory** — entities (clients, parties, GSTINs —
   extraction already finds them) + relationships as a graph over the index;
   shared agent memory scoped per workspace.
6. **Audit logs, SSO (SAML/OIDC), retention policies, DPA/SOC2 path** — gate
   for real enterprise contracts; mostly process + plumbing, schedule it, don't
   improvise it.

## 5. API platform

- **Public API v1** (served by Rudo Hub): `search`, `ask`, `documents` CRUD,
  `extract`, `deadlines`, `jobs`, webhooks. Versioned path (`/v1/`), cursor
  pagination, idempotency keys on writes.
- **AuthN/Z:** API keys per workspace (hashed at rest), OAuth for user-context
  apps later. **Billing:** metered on LLM-backed calls (ask/extract), flat on
  retrieval; Stripe metering; the proxy's plan/quota tables are the seed.
- **Rate limiting:** token-bucket in Redis (the audit's in-memory limiter is
  explicitly not carried forward).
- **SDKs:** Python + TypeScript, generated from the OpenAPI schema (FastAPI
  gives this nearly free) then hand-polished; SDKs, CLI, and MCP tools must
  present the *same verbs* — one mental model.
- **DX:** hosted docs from OpenAPI, a live "ask your sample docs" playground,
  quickstarts that mirror CLI commands 1:1.

---

# PART 4 — Phased execution roadmap

Effort = focused engineer-weeks (1–2 engineers + AI assistance). Elapsed time
assumes partial parallelism. Priorities: **M**ust / **S**hould / **N**ice.

## Phase 0 — V1 stabilization *(now → +6 wks · Lane A · M)*

- **Objectives:** the current app is shippable to strangers; the migration
  starts from a stable base.
- **Deliverables:** code signing + notarization (mac) & signing (win);
  production proxy deployed + `PROD_PROXY_URL` set (kills the CHANGE-ME TODO);
  PLAN-4 observability (both halves); release pipeline proven end-to-end
  (install → auto-update with data intact — Milestone 2 verified in anger).
- **Milestones:** signed installer through a full update cycle on both OSes.
- **Dependencies:** Apple/MS developer accounts; a host for the proxy.
- **Risks:** notarization/AV friction (time-boxed spikes).
- **Effort:** 3–4 wks. — **Also in this phase:** ratify the migration go/no-go
  (MIGRATION §0/§14); if NO-GO, execute PLAN-1→2→3→5 here instead (+6–8 wks).

## Phase 1 — FastAPI migration *(mo 1–4 · both lanes · M, gated on go)*

Everything per [MIGRATION-java-to-fastapi.md](MIGRATION-java-to-fastapi.md):
waves 0–6, contract + parity gates, dual-binary beta, cutover, Java deletion.
- **Milestones:** M1 parity harness green on core spine; M2 all 21 endpoints
  contract-green; M3 dual-binary beta cohort; M4 default-python release.
- **Risks:** Tika parity tail (§5.3), PyInstaller friction, scope creep
  ("improve while porting" — forbidden except items pre-listed in §14).
- **Effort:** 13–16.5 wks (§17).

## Phase 2 — V2 platform foundation *(mo 4–6 · Lane B enabler · M)*

- **Objectives:** the Python core becomes a reusable library; the cloud side
  becomes real.
- **Deliverables:** `rudo-core` importable package (API layer ≠ core layer
  enforced); tool registry v1 (§1.1) wrapping existing capabilities; Rudo Hub
  v0 = proxy rewritten on FastAPI + Postgres + Redis rate limiting (absorbs
  audit risk #5, MIGRATION C-6); opt-in telemetry (crash + anonymized usage
  counts) with a visible switch; localhost hardening (fail-closed token,
  tighten CORS — the deferred audit item).
- **Dependencies:** Phase 1.
- **Risks:** Hub scope creep — v0 is *only* auth/quota/billing-seed + the LLM
  passthrough, nothing else.
- **Effort:** 6–8 wks.

## Phase 3 — Agent framework *(mo 5–8, overlaps 2 · both lanes · S)*

- **Deliverables:** runtime + budgets + tracing (§1.1); Document Research
  agent shipped in chat as "Deep answer"; Report Generation agent (template →
  cited PDF); eval harness for agent outputs (extends the extraction gold-set
  idea — grounding-rate metric gates every prompt change).
- **Milestones:** research agent beats single-shot RAG on a 50-question gold
  set with ≥95% citation-grounding rate.
- **Dependencies:** Phase 2 tool registry.
- **Risks:** cost per query (budgets + caching mitigate); trust regression if
  agents hallucinate — grounding gate is non-negotiable.
- **Effort:** 6–8 wks.

## Phase 4 — MCP ecosystem *(mo 6–9 · Lane B · S — server itself borders M)*

- **Deliverables:** Rudo MCP server GA (§2.1) with docs + a "use your
  documents from Claude" quickstart; 2–3 ingestion connectors (Drive, Gmail)
  (§2.2); plugin manifest v0 (§2.3).
- **Dependencies:** Phase 2 registry; Phase 1 (Python MCP SDK).
- **Risks:** connector OAuth review queues (start Google verification early);
  scope creep into a connector farm — cap at 3 until pull is proven.
- **Effort:** 5–7 wks.

## Phase 5 — CLI release *(mo 8–10 · Lane B · S)*

- **Deliverables:** `rudo` CLI per §3 (read commands + index/extract/report +
  `mcp serve` + `workflow run` v0); pipx/brew distribution; repo/code-aware
  chunking (first Code Understanding slice); CI quickstart.
- **Dependencies:** Phases 2 (library), 4 (`mcp serve`).
- **Risks:** desktop/CLI store contention (lock-file + defer-to-HTTP rule,
  test it hard); Windows terminal quirks.
- **Effort:** 5–6 wks.

## Phase 6 — Enterprise *(mo 9–14 · Lane A ceiling · S→M if enterprise revenue is the goal)*

- **Deliverables:** workspaces + RBAC (§4.1–2); Rudo Hub v1 shared retrieval
  + sync (§4.3); audit logs; SSO; retention; security review + pen test;
  SOC2 Type-1 groundwork.
- **Milestones:** first design-partner team of ≥5 seats live on a shared
  workspace.
- **Dependencies:** Phases 2, 5 (admins live in terminals).
- **Risks:** this is where "local-first" meets "shared" — the sync/permission
  model must be designed once, carefully (biggest architecture decision of the
  year; budget a real design review before code).
- **Effort:** 10–14 wks.

## Phase 7 — Public API platform *(mo 12–16 · Lane B · S)*

- **Deliverables:** `/v1` public API + keys + metering/billing + rate limits
  (§5); Python & TS SDKs; docs + playground; GitHub Action.
- **Dependencies:** Phase 6 Hub (the API is a view over it).
- **Risks:** abuse/cost control (metering before marketing); support load.
- **Effort:** 6–8 wks.

## Phase 8 — Future expansion *(mo 16+ · N)*

Knowledge graph v2 (relationship queries in chat), workflow marketplace,
browser agents (revisit), mobile companion (read-only chat over Hub),
jurisdiction/vertical packs (CA-India templates as paid content — strong
Lane-A fit), local-LLM answer path (Ollama client already ports over — privacy
tier where even answers never leave the machine).

## Roadmap summary

| Phase | Window | Effort (wks) | Priority | Lane |
|---|---|---|---|---|
| 0 Stabilization | now–6 wk | 3–4 (+6–8 if no-go path) | M | A |
| 1 FastAPI migration | mo 1–4 | 13–16.5 | M (gated) | both |
| 2 Platform foundation | mo 4–6 | 6–8 | M | B |
| 3 Agents | mo 5–8 | 6–8 | S | both |
| 4 MCP | mo 6–9 | 5–7 | S (server ≈ M) | B |
| 5 CLI | mo 8–10 | 5–6 | S | B |
| 6 Enterprise | mo 9–14 | 10–14 | S/M | A |
| 7 Public API | mo 12–16 | 6–8 | S | B |
| 8 Future | 16+ | — | N | — |

**Total: ~55–70 engineer-weeks over 12–16 months** — realistic for 1–2
engineers with AI assistance, tight scope discipline, and the explicit
"not building" list (browser agents, network A2A, connector farm, second
plugin system) honored.

**The three decisions that matter most, in order:** (1) the migration go/no-go
at Phase 0 exit; (2) the shared-workspace sync/permission architecture at
Phase 6 entry; (3) whether enterprise (Lane A) or platform (Lane B) leads
after Phase 5 — decide with revenue data, not preference.
