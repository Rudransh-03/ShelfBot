// Local persistence for users + daily usage counters.
//
// Why SQLite: zero ops, single file on disk, more than fast enough at the
// scale this proxy will ever see (15 queries/user/day means the hot path is
// one UPDATE per query — sub-millisecond on commodity hardware).
//
// Switch to Postgres later if you ever need multi-region; the schema is
// identical and the queries are vanilla SQL.

import Database from 'better-sqlite3'
import { mkdirSync } from 'fs'
import { dirname }   from 'path'

export function openDb(path) {
  mkdirSync(dirname(path), { recursive: true })
  const db = new Database(path)
  db.pragma('journal_mode = WAL')
  db.pragma('synchronous = NORMAL')

  db.exec(`
    CREATE TABLE IF NOT EXISTS users (
      id          INTEGER PRIMARY KEY AUTOINCREMENT,
      email       TEXT    UNIQUE NOT NULL,
      created_at  TEXT    NOT NULL,
      last_login  TEXT    NOT NULL
    );

    CREATE TABLE IF NOT EXISTS usage (
      user_id      INTEGER NOT NULL,
      date         TEXT    NOT NULL,
      query_count  INTEGER NOT NULL DEFAULT 0,
      PRIMARY KEY (user_id, date),
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    );
  `)

  return {
    /** Look up or create a user by email. Idempotent. */
    upsertUser(email) {
      const now = new Date().toISOString()
      const existing = db.prepare('SELECT id, email, created_at FROM users WHERE email = ?').get(email)
      if (existing) {
        db.prepare('UPDATE users SET last_login = ? WHERE id = ?').run(now, existing.id)
        return existing
      }
      const insert = db.prepare(
        'INSERT INTO users (email, created_at, last_login) VALUES (?, ?, ?)'
      ).run(email, now, now)
      return { id: insert.lastInsertRowid, email, created_at: now }
    },

    findUserById(id) {
      return db.prepare('SELECT id, email, created_at FROM users WHERE id = ?').get(id)
    },

    /** Returns today's count for a user (0 if no row yet). */
    getTodayCount(userId, todayKey) {
      const row = db.prepare(
        'SELECT query_count FROM usage WHERE user_id = ? AND date = ?'
      ).get(userId, todayKey)
      return row?.query_count ?? 0
    },

    /**
     * Atomically increments today's counter and returns the new value.
     * Single-statement so two concurrent requests can't both slip through
     * a stale read of the limit.
     */
    incrementTodayCount(userId, todayKey) {
      db.prepare(`
        INSERT INTO usage (user_id, date, query_count) VALUES (?, ?, 1)
        ON CONFLICT(user_id, date) DO UPDATE SET query_count = query_count + 1
      `).run(userId, todayKey)
      return this.getTodayCount(userId, todayKey)
    },

    /**
     * Undoes an earlier increment — called when the upstream OpenAI call
     * fails so the user isn't billed a quota slot for our infra outage.
     * Never drops below zero.
     */
    decrementTodayCount(userId, todayKey) {
      db.prepare(
        'UPDATE usage SET query_count = MAX(0, query_count - 1) WHERE user_id = ? AND date = ?'
      ).run(userId, todayKey)
      return this.getTodayCount(userId, todayKey)
    },

    close() { db.close() },
  }
}

/** Day key in UTC. Matches the user's billing perception ("a day" = a calendar day). */
export function todayKey() {
  return new Date().toISOString().slice(0, 10) // "2026-05-23"
}
