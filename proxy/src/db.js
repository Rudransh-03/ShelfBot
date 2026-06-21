// Local persistence for devices, daily usage counters, and (when #12 lands)
// licenses.
//
// Identity model: each installation of ShelfBot generates a stable
// `device_id` (machine UUID from node-machine-id, with a UUID fallback
// persisted under userData on failure). That device_id is the unit of
// identity — no email, no password, no per-user account in the social
// sense. Every device starts on the "free" plan; a license_key (added
// in #12) flips it to "pro".
//
// Why this layout:
//   • One row per device, not per email. Means the only way to multiply
//     your free quota is to physically install on another machine.
//   • Per-plan daily caps live in env vars (FREE_DAILY / PRO_DAILY), read
//     once at startup. Lookup is a single integer compare on the hot path.
//   • A separate `licenses` table is created up-front (empty for now) so
//     the #12 payment integration is a pure code addition, not a schema
//     migration. The devices table has a nullable license_id pointing at it.

import Database from 'better-sqlite3'
import { mkdirSync } from 'fs'
import { dirname }   from 'path'

export function openDb(path) {
  mkdirSync(dirname(path), { recursive: true })
  const db = new Database(path)
  db.pragma('journal_mode = WAL')
  db.pragma('synchronous = NORMAL')
  db.pragma('foreign_keys = ON')

  db.exec(`
    CREATE TABLE IF NOT EXISTS licenses (
      id           INTEGER PRIMARY KEY AUTOINCREMENT,
      key          TEXT    UNIQUE NOT NULL,
      max_devices  INTEGER NOT NULL DEFAULT 3,
      created_at   TEXT    NOT NULL
    );

    CREATE TABLE IF NOT EXISTS devices (
      id          INTEGER PRIMARY KEY AUTOINCREMENT,
      device_id   TEXT    UNIQUE NOT NULL,
      plan        TEXT    NOT NULL DEFAULT 'free' CHECK (plan IN ('free', 'pro')),
      license_id  INTEGER NULL REFERENCES licenses(id) ON DELETE SET NULL,
      created_at  TEXT    NOT NULL,
      last_seen   TEXT    NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_devices_license ON devices(license_id);

    CREATE TABLE IF NOT EXISTS usage (
      device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
      date         TEXT    NOT NULL,
      query_count  INTEGER NOT NULL DEFAULT 0,
      PRIMARY KEY (device_id, date)
    );

    -- Per-day reorg "starts" counter — capped separately from chat queries
    -- because a reorg is a heavier, multi-call interaction. Same row layout
    -- as the usage table so the daily-reset semantics are identical (no
    -- explicit cron — the row just doesn't exist until the next day's
    -- first start).
    CREATE TABLE IF NOT EXISTS reorg_usage (
      device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
      date         TEXT    NOT NULL,
      start_count  INTEGER NOT NULL DEFAULT 0,
      PRIMARY KEY (device_id, date)
    );

    -- One row per active reorg session. The backend opens a session via
    -- /reorg/start (which decrements the per-day cap), then uses sessionId
    -- to draw from a fixed per-session LLM call budget. Sessions are
    -- short-lived (~30 min) and the row is never updated after expiry —
    -- expired rows are just ignored by the decrement query.
    CREATE TABLE IF NOT EXISTS reorg_sessions (
      id                TEXT    PRIMARY KEY,
      device_id         INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
      budget_remaining  INTEGER NOT NULL,
      budget_initial    INTEGER NOT NULL,
      created_at        TEXT    NOT NULL,
      expires_at        TEXT    NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_reorg_sessions_device ON reorg_sessions(device_id);
    CREATE INDEX IF NOT EXISTS idx_reorg_sessions_expiry ON reorg_sessions(expires_at);

    -- Account identity (Google sign-in). Replaces anonymous device identity as
    -- the unit a plan/trial is bound to, so reinstalling can't mint a fresh
    -- trial: the trial sticks to the Google account, not the install. One row
    -- per verified Google user.
    --   plan='trial' starts a 24h, chat-only trial (cap = TRIAL_DAILY).
    --   plan='pro'   is a paid subscriber (set later by the billing webhook).
    -- ls_* columns hold the LemonSqueezy customer/subscription ids once billing
    -- lands; null until then.
    CREATE TABLE IF NOT EXISTS accounts (
      id                 INTEGER PRIMARY KEY AUTOINCREMENT,
      email              TEXT    UNIQUE NOT NULL,
      google_sub         TEXT    UNIQUE NOT NULL,
      plan               TEXT    NOT NULL DEFAULT 'trial' CHECK (plan IN ('trial','pro')),
      trial_started_at   TEXT    NOT NULL,
      ls_customer_id     TEXT,
      ls_subscription_id TEXT,
      created_at         TEXT    NOT NULL,
      last_seen          TEXT    NOT NULL
    );

    -- Per-day chat counter for accounts. Same daily-reset semantics as the
    -- device usage table (no cron — the row just doesn't exist until the
    -- next day's first chat).
    CREATE TABLE IF NOT EXISTS account_usage (
      account_id   INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
      date         TEXT    NOT NULL,
      chat_count   INTEGER NOT NULL DEFAULT 0,
      PRIMARY KEY (account_id, date)
    );
  `)

  // Drop pre-#11.5 tables if present so leftover dev rows from the old
  // email-based schema don't sit around confusing anyone. The proxy is
  // pre-production; no real customer data ever lived in those tables.
  try { db.exec(`DROP TABLE IF EXISTS users;`) } catch {}

  return {
    /**
     * Registers a device (or returns the existing row if it's been seen
     * before). Idempotent — calling repeatedly with the same device_id is
     * a no-op write but always returns the canonical row.
     */
    upsertDevice(deviceId) {
      const now = new Date().toISOString()
      const existing = db.prepare(
        'SELECT id, device_id, plan, license_id, created_at FROM devices WHERE device_id = ?'
      ).get(deviceId)
      if (existing) {
        db.prepare('UPDATE devices SET last_seen = ? WHERE id = ?').run(now, existing.id)
        return existing
      }
      const ins = db.prepare(
        'INSERT INTO devices (device_id, plan, created_at, last_seen) VALUES (?, ?, ?, ?)'
      ).run(deviceId, 'free', now, now)
      return {
        id:         ins.lastInsertRowid,
        device_id:  deviceId,
        plan:       'free',
        license_id: null,
        created_at: now,
      }
    },

    /** Lookup by primary key. Used by JWT validation. */
    findDeviceById(id) {
      return db.prepare(
        'SELECT id, device_id, plan, license_id, created_at FROM devices WHERE id = ?'
      ).get(id)
    },

    /** Returns today's count for a device (0 if no row yet). */
    getTodayCount(deviceRowId, todayKey) {
      const row = db.prepare(
        'SELECT query_count FROM usage WHERE device_id = ? AND date = ?'
      ).get(deviceRowId, todayKey)
      return row?.query_count ?? 0
    },

    /**
     * Atomically increments today's counter and returns the new value.
     * Single-statement upsert so two concurrent requests can't both slip
     * through a stale read.
     */
    incrementTodayCount(deviceRowId, todayKey) {
      db.prepare(`
        INSERT INTO usage (device_id, date, query_count) VALUES (?, ?, 1)
        ON CONFLICT(device_id, date) DO UPDATE SET query_count = query_count + 1
      `).run(deviceRowId, todayKey)
      return this.getTodayCount(deviceRowId, todayKey)
    },

    /**
     * Undoes a prior increment — used when the OpenAI upstream call fails
     * so the device isn't charged a slot for our infra outage. Floors at 0.
     */
    decrementTodayCount(deviceRowId, todayKey) {
      db.prepare(
        'UPDATE usage SET query_count = MAX(0, query_count - 1) WHERE device_id = ? AND date = ?'
      ).run(deviceRowId, todayKey)
      return this.getTodayCount(deviceRowId, todayKey)
    },

    // ── Reorg quota & sessions ──────────────────────────────────────────────

    /** Today's reorg-start count for a device. 0 if no row yet. */
    getReorgStartsToday(deviceRowId, todayKey) {
      const row = db.prepare(
        'SELECT start_count FROM reorg_usage WHERE device_id = ? AND date = ?'
      ).get(deviceRowId, todayKey)
      return row?.start_count ?? 0
    },

    /** Atomically increments today's reorg-start count. Same upsert shape as chat usage. */
    incrementReorgStartsToday(deviceRowId, todayKey) {
      db.prepare(`
        INSERT INTO reorg_usage (device_id, date, start_count) VALUES (?, ?, 1)
        ON CONFLICT(device_id, date) DO UPDATE SET start_count = start_count + 1
      `).run(deviceRowId, todayKey)
      return this.getReorgStartsToday(deviceRowId, todayKey)
    },

    /**
     * Refunds a prior start increment — used when /reorg/start successfully
     * decrements the day cap but then fails to create the session row. Floors at 0.
     */
    decrementReorgStartsToday(deviceRowId, todayKey) {
      db.prepare(
        'UPDATE reorg_usage SET start_count = MAX(0, start_count - 1) WHERE device_id = ? AND date = ?'
      ).run(deviceRowId, todayKey)
      return this.getReorgStartsToday(deviceRowId, todayKey)
    },

    /**
     * Creates a new reorg session. The caller is responsible for generating
     * a unique {@code sessionId} (UUID) and computing the absolute expiry.
     */
    createReorgSession(sessionId, deviceRowId, budget, expiresAtIso) {
      const now = new Date().toISOString()
      db.prepare(`
        INSERT INTO reorg_sessions
          (id, device_id, budget_remaining, budget_initial, created_at, expires_at)
        VALUES (?, ?, ?, ?, ?, ?)
      `).run(sessionId, deviceRowId, budget, budget, now, expiresAtIso)
    },

    /**
     * Returns the session row, or undefined if it doesn't exist OR is
     * expired. Expired sessions are not auto-deleted (cheap to keep, and
     * useful for forensics) — we just don't act on them.
     */
    getActiveReorgSession(sessionId, nowIso) {
      return db.prepare(`
        SELECT id, device_id, budget_remaining, budget_initial, created_at, expires_at
        FROM reorg_sessions
        WHERE id = ? AND expires_at > ?
      `).get(sessionId, nowIso)
    },

    /**
     * Atomically decrements the session's budget IF it's still > 0 AND the
     * session hasn't expired. Returns the new budget on success, or -1 if
     * the session was either exhausted or expired. Race-safe: SQLite
     * serializes writes, and the WHERE clause makes the check + decrement
     * a single statement.
     */
    decrementReorgSessionBudget(sessionId, nowIso) {
      const r = db.prepare(`
        UPDATE reorg_sessions
        SET budget_remaining = budget_remaining - 1
        WHERE id = ? AND budget_remaining > 0 AND expires_at > ?
      `).run(sessionId, nowIso)
      if (r.changes === 0) return -1
      return db.prepare(
        'SELECT budget_remaining FROM reorg_sessions WHERE id = ?'
      ).get(sessionId).budget_remaining
    },

    /**
     * Undoes a budget decrement — used when the OpenAI upstream call fails.
     * Capped at budget_initial so a buggy caller can't inflate a session.
     */
    refundReorgSessionBudget(sessionId) {
      db.prepare(`
        UPDATE reorg_sessions
        SET budget_remaining = MIN(budget_initial, budget_remaining + 1)
        WHERE id = ?
      `).run(sessionId)
    },

    // ── Accounts (Google sign-in) ───────────────────────────────────────────

    /**
     * Finds the account for a Google subject id, or creates a fresh trial
     * account if it's the first time we've seen them. Idempotent on google_sub:
     * the SAME person signing in from a new install resolves to the SAME row
     * (and the SAME, already-consumed trial). Email is refreshed in case it
     * changed upstream. Returns the canonical row.
     */
    upsertAccountByGoogle({ sub, email }) {
      const now = new Date().toISOString()
      const existing = db.prepare('SELECT * FROM accounts WHERE google_sub = ?').get(sub)
      if (existing) {
        db.prepare('UPDATE accounts SET last_seen = ?, email = ? WHERE id = ?').run(now, email, existing.id)
        return { ...existing, email, last_seen: now }
      }
      const ins = db.prepare(`
        INSERT INTO accounts (email, google_sub, plan, trial_started_at, created_at, last_seen)
        VALUES (?, ?, 'trial', ?, ?, ?)
      `).run(email, sub, now, now, now)
      return db.prepare('SELECT * FROM accounts WHERE id = ?').get(ins.lastInsertRowid)
    },

    /** Lookup by primary key. Used by JWT validation for account tokens. */
    findAccountById(id) {
      return db.prepare('SELECT * FROM accounts WHERE id = ?').get(id)
    },

    /** Today's chat count for an account (0 if no row yet). */
    getAccountChatToday(accountId, todayKey) {
      const row = db.prepare(
        'SELECT chat_count FROM account_usage WHERE account_id = ? AND date = ?'
      ).get(accountId, todayKey)
      return row?.chat_count ?? 0
    },

    /** Atomically increments today's account chat count; returns the new value. */
    incrementAccountChatToday(accountId, todayKey) {
      db.prepare(`
        INSERT INTO account_usage (account_id, date, chat_count) VALUES (?, ?, 1)
        ON CONFLICT(account_id, date) DO UPDATE SET chat_count = chat_count + 1
      `).run(accountId, todayKey)
      return this.getAccountChatToday(accountId, todayKey)
    },

    /** Refunds a prior account-chat increment (upstream failure). Floors at 0. */
    decrementAccountChatToday(accountId, todayKey) {
      db.prepare(
        'UPDATE account_usage SET chat_count = MAX(0, chat_count - 1) WHERE account_id = ? AND date = ?'
      ).run(accountId, todayKey)
      return this.getAccountChatToday(accountId, todayKey)
    },

    /**
     * Sets an account's plan (and optional LemonSqueezy ids). Used later by the
     * billing webhook to flip 'trial' → 'pro' (or back on cancellation).
     */
    setAccountPlan(accountId, plan, { lsCustomerId = null, lsSubscriptionId = null } = {}) {
      db.prepare(`
        UPDATE accounts
        SET plan = ?,
            ls_customer_id     = COALESCE(?, ls_customer_id),
            ls_subscription_id = COALESCE(?, ls_subscription_id)
        WHERE id = ?
      `).run(plan, lsCustomerId, lsSubscriptionId, accountId)
      return this.findAccountById(accountId)
    },

    close() { db.close() },
  }
}

/** Day key in UTC. */
export function todayKey() {
  return new Date().toISOString().slice(0, 10) // "2026-05-23"
}
