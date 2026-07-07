# Execution Protocol — how to execute any plan in this directory

> **📍 Navigation:** [Index](README.md) · [Tracker](TRACKER.md)
>
> **Who this is for:** any model or engineer executing a `PLAN-*.md`. Follow
> the 10 steps IN ORDER. Do not skip steps. Do not improvise beyond a plan's
> written scope. When something doesn't match reality, use the
> [Failure playbook](#failure-playbook) — never guess.

## Environment facts (memorize these)

```bash
# Repo root
cd /Users/shresth1811/Documents/PROJECTS/ShelfBot

# Java builds NEED this exact JAVA_HOME (newer JDKs on this machine break the build):
export JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home

# The three verification commands (run from the listed directory):
cd backend && mvn -B -ntp test           # PASS = "Tests run: 417+, Failures: 0, Errors: 0"
cd ui && node --test test/*.test.mjs     # PASS = 0 fail (23+ tests)
cd ui && npm run build                   # PASS = exit 0
```

Key code locations: backend Java under
`backend/src/main/java/com/localfilebrain/`, tests under
`backend/src/test/java/...`, Electron main `ui/src/main/`, renderer
`ui/src/renderer/src/`, cloud proxy `proxy/src/`.

## The 10 steps

### Step 0 — Preconditions
1. `git status --short` — note what's already modified. You will NOT touch
   files outside your plan's "Files to touch" list.
2. Run all three verification commands ONCE to establish a green baseline.
   If the baseline is already red, STOP and report — do not start a plan on a
   broken tree.

### Step 1 — Confirm the plan is live
Open [TRACKER.md](TRACKER.md). If your plan's status is `DONE` or
`SUPERSEDED`, STOP and report why. If another plan is `IN PROGRESS`, STOP and
ask. Check the [Decision log](README.md#decision-log): PLAN-1/2/3/5 are
superseded when D-1 = GO.

### Step 2 — Read the ENTIRE plan file
Not just the steps: the Goal (tells you what "working" means), Files-to-touch
(your allowlist), Edge cases (traps written specifically for you), Acceptance
criteria (your exit exam). Set TRACKER status to `IN PROGRESS`.

### Step 3 — Build a todo list
One todo per plan step + one todo per test file + one "run acceptance
criteria" todo + one "update TRACKER" todo. Work them strictly in order.

### Step 4 — Verify every anchor BEFORE editing
For every file/method/line the plan references, confirm it exists first:
```bash
grep -n "<symbol from the plan>" <file from the plan>
```
Line numbers may have drifted — that is fine; the SYMBOL must exist. If a
referenced symbol does not exist at all → Failure playbook F-1.

### Step 5 — Implement one step at a time
- After every backend-touching step:
  `cd backend && mvn -q -B -ntp compile` — must print nothing/succeed before
  the next step.
- Follow the plan's code snippets closely; where a snippet says `…` you fill
  in mechanically from the surrounding description, never inventing new
  behavior.
- HARD RULES (violating any = failed execution):
  1. Never rename/remove a field in an HTTP response.
  2. Never touch files outside the plan's list (test files of touched code OK).
  3. Never `git commit` or `git push`.
  4. Never "improve" neighboring code. No drive-by refactors.
  5. Never delete a test to make the suite pass — see F-2.

### Step 6 — Write the tests the plan specifies
Tests are part of the deliverable, same PR-quality bar as the code. Copy the
construction patterns of the neighboring test class the plan names (e.g.
"mirror `DataMigratorTest`" means: same `@TempDir` style, same assertion
library).

### Step 7 — Run the full verification block
All three commands from Environment facts. All green or you are not done.

### Step 8 — Run the plan's Acceptance criteria, one by one
Each criterion is a literal command or check. Run it, record PASS/FAIL. A
single FAIL = the plan is not done; go back to the failing step.

### Step 9 — Update [TRACKER.md](TRACKER.md)
Set status `DONE (YYYY-MM-DD)`, tick exactly the boxes you verified, add a
one-line note for any deviation (see Step 10).

### Step 10 — Final report
Report in this order: (1) what changed, file by file, one line each;
(2) verification results with the actual test counts; (3) every deviation
from the plan and why; (4) anything discovered that the plans should record
(propose a TRACKER note — do not silently edit other plan docs).

## Failure playbook

| # | Situation | What to do |
|---|---|---|
| F-1 | A symbol/file the plan references doesn't exist | STOP that step. Search for where it moved: `grep -rn "<symbol>" backend/src ui/src`. If found → proceed against the new location and record the drift in your report. If truly gone → the plan is stale; report and halt the plan. |
| F-2 | A test fails after your change | Diagnose whether YOUR change broke it. If yes → fix your change, not the test — unless the plan explicitly says that test's expectations change (e.g. batch counts), in which case update it exactly as the plan states. |
| F-3 | Baseline (Step 0) is already red | Report the failing tests verbatim and stop. Never start on red. |
| F-4 | Two plan instructions contradict each other | The more specific instruction wins; record the conflict in your report and in a TRACKER note. |
| F-5 | You need information the plan doesn't give | Look in this order: the plan's Edge-cases section → [README](README.md) → the referenced source file itself → the audit (`production_report.md`). Still stuck → stop and ask; do not guess. |
| F-6 | A verification command itself errors (env problem) | Check JAVA_HOME (Environment facts); check you're in the right directory; `mvn` must run from `backend/`, node tests from `ui/`. |

## Scope discipline in one sentence

**The plan is the contract: everything it says, nothing it doesn't.**
