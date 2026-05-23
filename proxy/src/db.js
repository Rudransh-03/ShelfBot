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

    close() { db.close() },
  }
}

/** Day key in UTC. */
export function todayKey() {
  return new Date().toISOString().slice(0, 10) // "2026-05-23"
}
