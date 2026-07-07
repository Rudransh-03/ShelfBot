---
name: rudo-roadmap
description: Answer strategy and roadmap questions about Rudo V2 (AI agents, MCP, CLI, enterprise, public API platform, phases 0-8) from plans/ROADMAP-rudo-v2.md, or turn a roadmap phase into executable PLAN files. Use for "roadmap", "V2", "what phase", "agents", "MCP", "CLI plans", "enterprise features", "when do we build X".
---

You are working with `plans/ROADMAP-rudo-v2.md` — Rudo's 12–18 month product
strategy: two-lane framing (§0), agents (§1), MCP (§2), CLI (§3), enterprise
(§4), API platform (§5), and execution Phases 0–8 with effort/priority/risks.

## To ANSWER strategy questions

1. Look it up in the roadmap — quote the section. Key anchors: §0 for the
   "desktop assistant vs developer platform" tension and the two-lane ruling;
   the per-phase blocks for timing/effort/priority; the roadmap-summary table
   for the whole picture; the explicit **not-building list** (browser agents,
   network A2A, connector farm, second plugin system) — cite it when someone
   proposes one of those.
2. Cross-check dependencies: phases gate on the FastAPI migration
   (`plans/MIGRATION-java-to-fastapi.md`) and on decisions D-1/D-2/D-3 in the
   `plans/README.md` Decision log. Never present a Lane-B feature as buildable
   before Phase 1/2 are done.
3. Status of anything = `plans/TRACKER.md`, never memory.

## To turn a phase into executable work ("start phase N", "plan the MCP server")

1. Confirm the phase's dependencies are DONE in `plans/TRACKER.md`; if not,
   say what blocks it and stop.
2. Write one `plans/PLAN-<slug>.md` PER DELIVERABLE of that phase, copying the
   exact format of the existing PLAN files: Goal → Files to touch →
   Steps in order → Edge cases a weaker model would miss → Acceptance criteria,
   plus the standard navigation header linking README/EXECUTION-PROTOCOL/TRACKER.
3. Add a row for each new plan to the `plans/TRACKER.md` status board and to
   the document map in `plans/README.md`.
4. Execution of those new plans then goes through `plans/EXECUTION-PROTOCOL.md`
   (the `/rudo-plan` skill), same as PLAN-1..5.
