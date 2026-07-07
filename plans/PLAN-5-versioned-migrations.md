# PLAN 5 — Versioned Data Migrations

> **📍 Navigation:** [Index](README.md) · [How to execute](EXECUTION-PROTOCOL.md) · [Tracker](TRACKER.md) · Prev: [PLAN-4](PLAN-4-observability-diagnostics.md) · Next: [MIGRATION](MIGRATION-java-to-fastapi.md)
> **Execute when:** Decision **D-1 ≠ GO** ([Decision log](README.md#decision-log)). If D-1 = GO, Alembic replaces this — [MIGRATION §6](MIGRATION-java-to-fastapi.md#6-database-layer-migration) — but its backup-before-migrate and refuse-newer semantics carry over as policy.
> **Executor:** follow [EXECUTION-PROTOCOL.md](EXECUTION-PROTOCOL.md); record in [TRACKER.md](TRACKER.md#plan-5). Must land before ANY release that alters a SQLite table.

## Goal

A formal, versioned migration framework for the two backend SQLite databases
(`shelfbot-metadata.db` via `IndexMetadataStore`, `shelfbot-chats.db` via
`ChatStore`), replacing "additive `CREATE TABLE IF NOT EXISTS` forever" as the
only evolution mechanism. Today any column rename/type change has no path, a
downgrade is undefined behavior, and the only "migration" precedent is the
embedding-change full wipe in `Main.resetIfEmbeddingChanged`. After this plan:

- Each DB carries a schema version (SQLite's built-in `PRAGMA user_version`).
- Migrations are an ordered, append-only list, each running in one transaction.
- Opening a NEWER-versioned DB (user downgraded the app) fails fast with a
  clear error instead of corrupting.
- A timestamped backup of the DB file is taken before any migration runs.

This does NOT migrate anything today — it lands the framework plus a v1
baseline so the NEXT schema change is a 5-line diff.

## Files to touch

1. NEW `backend/src/main/java/com/localfilebrain/storage/SchemaMigrator.java`
2. `backend/src/main/java/com/localfilebrain/ingestion/IndexMetadataStore.java`
3. `backend/src/main/java/com/localfilebrain/chat/ChatStore.java`
4. NEW `backend/src/test/java/com/localfilebrain/storage/SchemaMigratorTest.java`

Do NOT touch: the proxy's DB (separate deployable with its own lifecycle),
`DataMigrator` (location migration, orthogonal), Lucene index versioning
(the `.embedding-meta` marker already covers vector compatibility).

## Steps, in order

### Step 1 — `SchemaMigrator`

```java
package com.localfilebrain.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Minimal versioned-migration runner for the app's embedded SQLite databases.
 *
 * Versioning uses SQLite's built-in {@code PRAGMA user_version} (an int stored
 * in the DB header — no extra table). Rules:
 *   • Migrations are an ordered, append-only list; version N's migration
 *     brings a version N-1 database to N.
 *   • Each migration runs inside ONE transaction together with the
 *     user_version bump, so a crash mid-migration leaves the previous version
 *     intact (SQLite DDL is transactional).
 *   • A database whose user_version is NEWER than the latest known migration
 *     was written by a newer app: refuse to open (throw MigrationException)
 *     rather than risk corrupting it.
 *   • Before running any migration on a non-empty DB, copy the db file to
 *     "<name>.pre-v<target>.bak" beside it (best-effort WAL checkpoint first).
 *     Keep at most 2 such backups per DB (delete oldest).
 */
public final class SchemaMigrator {

    /** One schema step. {@code apply} runs inside an open transaction. */
    public record Migration(int version, String description, SqlWork apply) {}
    @FunctionalInterface public interface SqlWork { void run(Connection c) throws SQLException; }

    public static class MigrationException extends RuntimeException {
        public MigrationException(String m) { super(m); }
        public MigrationException(String m, Throwable t) { super(m, t); }
    }

    /**
     * @param connection open SQLite connection (the store's own)
     * @param dbFile     path of the db file (for backups); null = skip backups (tests)
     * @param baseline   runs when user_version==0: either a fresh DB (create
     *                   full current schema) or a pre-framework DB (tables
     *                   already exist — every statement must be IF NOT EXISTS /
     *                   idempotent). After baseline, version is stamped to
     *                   {@code baselineVersion} WITHOUT running migrations <= it.
     * @param migrations ordered, versions strictly increasing, all > baselineVersion
     */
    public static void migrate(Connection connection, java.nio.file.Path dbFile,
                               int baselineVersion, SqlWork baseline,
                               List<Migration> migrations) { … }
}
```

Implementation details (follow exactly):

1. Read version: `SELECT` via `Statement.executeQuery("PRAGMA user_version")`,
   column 1 int.
2. Compute `latest` = baselineVersion if migrations empty, else last
   migration's version. Validate: versions strictly increasing, each >
   baselineVersion; throw `MigrationException` on a malformed list (programmer
   error caught in tests, not in the field).
3. If `current > latest` → throw
   `MigrationException("Database <file> is version <current>, but this build understands only <latest>. It was likely written by a newer version of Rudo — please update the app.")`
4. If `current == 0`: run `baseline` inside a transaction, then
   `PRAGMA user_version = <baselineVersion>` in the SAME transaction; set
   `current = baselineVersion`. (No backup for version-0 → baseline: on a fresh
   DB there is nothing to back up, and on a pre-framework DB baseline is
   idempotent IF-NOT-EXISTS DDL only.)
5. If `current < latest`: take the backup (step 6), then for each migration
   with `version > current`, in order: begin transaction
   (`connection.setAutoCommit(false)`), run `apply`, run
   `PRAGMA user_version = <version>` (NOTE: `PRAGMA user_version = N` cannot
   use bind parameters — build the string from the int; it is not user input),
   commit, restore autocommit. On any exception: rollback, restore autocommit,
   wrap in `MigrationException` naming the version + description, rethrow.
6. Backup: best-effort `PRAGMA wal_checkpoint(TRUNCATE)` (swallow errors),
   then `Files.copy(dbFile, sibling(dbFile.getFileName() + ".pre-v" + latest + ".bak"), REPLACE_EXISTING)`.
   Then list siblings matching `<name>.pre-v*.bak`, sort by name, delete all
   but the newest 2. All backup failures LOG WARN and continue — a failed
   backup must not block startup (the transaction is the real safety).

### Step 2 — Wire into `IndexMetadataStore`

In the constructor, replace the direct `initSchema()` call:

```java
SchemaMigrator.migrate(
        connection,
        dbPath,
        /* baselineVersion = */ 1,
        c -> initSchemaStatements(c),   // see below
        java.util.List.of()             // no migrations yet — next change adds v2 here
);
```

- Rename the existing `initSchema()` to
  `private static void initSchemaStatements(Connection c) throws SQLException`
  taking the connection as a parameter (it currently uses the field; make it
  static-with-param so the migrator can call it). Every statement in it is
  already `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` — verify
  by grep that there are no non-idempotent statements
  (`grep -n "executeUpdate" IndexMetadataStore.java` inside initSchema and
  confirm each line contains `IF NOT EXISTS`).
- The `MigrationException` propagates out of the constructor as today's
  `MetadataStoreException` would — wrap it:
  `catch (SchemaMigrator.MigrationException e) { throw new MetadataStoreException(e.getMessage(), e); }`
  so `Main.runServerMode`'s existing fallback/warn path handles it. The
  refuse-downgrade message then reaches the supervisor's startup-failure
  surface (Milestone 2), which is exactly the desired UX.

### Step 3 — Wire into `ChatStore`

Identical pattern: baselineVersion 1, baseline = its current
`initSchema` body (same rename-to-static-with-param treatment), empty
migrations list, wrap `MigrationException` in `ChatStoreException`.

### Step 4 — Prove the mechanism with a real (dormant) v2 example in tests only

Do NOT add a v2 migration to production code. The test suite defines its own
schema + migrations to exercise the runner (below). Production stays at v1
until a real schema change needs v2 — the framework's value is that that
future diff is: append one `Migration(2, "...", c -> …ALTER TABLE…)` entry.

### Step 5 — `SchemaMigratorTest`

Use temp SQLite files (`DriverManager.getConnection("jdbc:sqlite:" + tmp)`),
mirroring `DataMigratorTest` style. Tests:

1. `freshDb_runsBaseline_andStampsVersion` — user_version==0, empty file →
   baseline creates a table; `PRAGMA user_version` == 1; table exists.
2. `preFrameworkDb_baselineIsIdempotent` — create the table manually first
   (simulating an existing install), user_version still 0 → migrate runs
   baseline (IF NOT EXISTS no-ops), stamps 1, data in the table survives.
3. `migrationsRunInOrder_andStampEachVersion` — baseline v1 (table t(a)),
   migrations v2 (`ALTER TABLE t ADD COLUMN b`), v3 (`ALTER TABLE t ADD COLUMN c`)
   → user_version 3; both columns exist; insert/select works.
4. `alreadyLatest_isNoOp` — run migrate twice; second run executes nothing
   (verify with a migration whose lambda increments a counter — counter stays
   at 1).
5. `newerDbIsRefused` — set `PRAGMA user_version = 99` manually → migrate
   throws `MigrationException` mentioning versions; DB unchanged.
6. `failedMigrationRollsBack` — v2 lambda inserts a row THEN throws →
   after the exception, user_version is still 1 and the row is absent
   (transaction rolled back).
7. `backupCreated_andPruned` — dbFile provided; run an upgrade → a
   `*.pre-v*.bak` sibling exists; run three distinct upgrades (v2, then v3,
   then v4 via three migrate calls) → at most 2 `.bak` files remain.
8. `malformedMigrationList_throws` — non-increasing versions rejected.

Also add one integration-ish assertion to the EXISTING store tests' setup
paths (no new file): after constructing an `IndexMetadataStore` on a temp dir,
`PRAGMA user_version` is 1 — put this as one new test method inside
`SchemaMigratorTest` using the real store class, to pin the wiring.

## Edge cases a weaker model would miss

1. **`PRAGMA user_version = ?` cannot be a prepared-statement parameter** —
   SQLite pragmas don't bind. String-concatenate the int (safe: it is an int
   you control), or the migrator silently sets nothing.
2. **Autocommit discipline**: sqlite-jdbc default is autocommit ON. The
   transaction bracket is `setAutoCommit(false) … commit()/rollback() …
   setAutoCommit(true)` in a finally — miss the finally and every later store
   method runs inside a stale transaction (WAL grows, nothing durably commits).
3. **DDL in transactions**: SQLite DDL IS transactional — rely on it; do not
   add "manual undo" logic.
4. **Baseline must not take the backup path** — pre-framework DBs are stamped
   via idempotent DDL; backing up before a no-op churns user disks on first
   launch after update. Backups only when `current >= baselineVersion` and
   `current < latest`.
5. **WAL siblings**: copying just the `.db` file while `-wal` has unflushed
   frames loses recent writes in the BACKUP (not the live DB) — that's why the
   checkpoint runs first; and why checkpoint failure only logs (the live DB is
   still transactionally safe).
6. **Do not renumber or edit an existing migration once shipped** — add a
   comment saying exactly that above the migrations list in both stores.
7. **Both stores open other pragmas first** (`journal_mode=WAL`,
   `synchronous=NORMAL`, ChatStore adds `foreign_keys=ON`) — keep those BEFORE
   the migrate call, order unchanged.
8. **`user_version` is per-database-file** and survives WAL/vacuum — no extra
   persistence needed; do not invent a version table.
9. **Error message quality is the feature**: the refuse-downgrade message must
   name the file, both versions, and say "update the app" — it will be shown
   to end users via the supervisor's failure state.

## Acceptance criteria

1. All 8+ `SchemaMigratorTest` tests pass.
2. Constructing `IndexMetadataStore`/`ChatStore` on a temp dir yields
   `PRAGMA user_version == 1` (pinned by test).
3. Opening a pre-existing (pre-framework) metadata DB — simulate by creating a
   store with the OLD code path… not possible post-change; instead the
   `preFrameworkDb_baselineIsIdempotent` test with a manually-created
   `file_index` table stands in for it and must pass with data intact.
4. Full backend suite: 0 failures, 0 errors (the ~417 existing tests all
   construct stores — they collectively regression-test the wiring).
5. `grep -n "user_version" backend/src/main/java` shows it only in
   `SchemaMigrator` (single source of truth).
6. UI suite/build untouched and green.
