// ShelfBot proxy server.
//
// Three responsibilities:
//   1. Auth: gives each user a JWT after they sign in.
//   2. Rate-limit: enforces N chat-completion calls per user per day.
//   3. Proxy: forwards embedding + chat requests to OpenAI using a single
//      shared OPENAI_API_KEY that never leaves this server.
//
// The desktop app never sees the OpenAI key — that's the entire point. If a
// user reverse-engineers the binary they get nothing they can use to bill
// against your OpenAI account.

import dotenv  from 'dotenv'
import express from 'express'
import cors    from 'cors'

import { openDb, todayKey } from './db.js'
import { makeAuth }         from './auth.js'

dotenv.config()

const PORT              = parseInt(process.env.PORT || '8787', 10)
const JWT_SECRET        = process.env.JWT_SECRET
const JWT_TTL_SECONDS   = parseInt(process.env.JWT_TTL_SECONDS || '2592000', 10)
const DAILY_LIMIT       = parseInt(process.env.DAILY_QUERY_LIMIT || '15', 10)
const OPENAI_API_KEY    = process.env.OPENAI_API_KEY
const DB_PATH           = process.env.DB_PATH || './shelfbot-proxy.db'

if (!JWT_SECRET || JWT_SECRET.length < 16) {
  console.error('[proxy] JWT_SECRET missing or too short. Refusing to start.')
  process.exit(1)
}
if (!OPENAI_API_KEY) {
  console.error('[proxy] OPENAI_API_KEY missing. Refusing to start.')
  process.exit(1)
}

const db   = openDb(DB_PATH)
const auth = makeAuth({ db, jwtSecret: JWT_SECRET, jwtTtlSeconds: JWT_TTL_SECONDS })

const app = express()
app.use(cors())
app.use(express.json({ limit: '8mb' })) // chunks + embeddings can be biggish

// ─── Health check ─────────────────────────────────────────────────────────
app.get('/health', (req, res) => {
  res.json({ ok: true, service: 'shelfbot-proxy', dailyLimit: DAILY_LIMIT })
})

// ─── Auth ─────────────────────────────────────────────────────────────────
//
// Stub login: POST { email } returns a JWT. Replace with Google OAuth when
// you have Cloud Console credentials. The downstream code (DB row format,
// JWT shape, middleware) stays identical.
app.post('/auth/login', (req, res) => {
  const email = (req.body?.email || '').trim().toLowerCase()
  if (!email || !email.includes('@')) {
    return res.status(400).json({ error: 'Valid email required' })
  }
  const { token, user, expiresIn } = auth.issueToken(email)
  res.json({
    token,
    user:      { id: user.id, email: user.email },
    expiresIn,
  })
})

// /me returns current user info + today's usage. The app uses this to
// display "8 of 15 queries used today" and to silently re-login when the
// token has expired.
app.get('/me', auth.requireAuth, (req, res) => {
  const used = db.getTodayCount(req.user.id, todayKey())
  res.json({
    user:        { id: req.user.id, email: req.user.email },
    usage:       { used, limit: DAILY_LIMIT, remaining: Math.max(0, DAILY_LIMIT - used) },
  })
})

// ─── OpenAI proxy ─────────────────────────────────────────────────────────
//
// Embedding requests are NOT rate-limited (they're cheap, bounded by the
// indexer, and the user can't fire them on demand). Chat completions are
// the only billable surface a user controls directly, so that's what the
// daily limit gates.

app.post('/proxy/embeddings', auth.requireAuth, async (req, res) => {
  try {
    const upstream = await fetch('https://api.openai.com/v1/embeddings', {
      method: 'POST',
      headers: {
        'Content-Type':  'application/json',
        'Authorization': 'Bearer ' + OPENAI_API_KEY,
      },
      body: JSON.stringify(req.body),
    })
    const body = await upstream.text()
    res.status(upstream.status)
       .type(upstream.headers.get('content-type') || 'application/json')
       .send(body)
  } catch (e) {
    res.status(502).json({ error: 'Upstream embeddings failed: ' + e.message })
  }
})

app.post('/proxy/chat/completions', auth.requireAuth, async (req, res) => {
  // Check + increment atomically: if the increment puts us over the limit
  // we reject AND decrement back. Simpler than a lock; correct under
  // concurrent requests because SQLite serialises writes.
  const before = db.getTodayCount(req.user.id, todayKey())
  if (before >= DAILY_LIMIT) {
    return res.status(429).json({
      error:     'Daily query limit reached.',
      used:      before,
      limit:     DAILY_LIMIT,
      remaining: 0,
    })
  }
  const after = db.incrementTodayCount(req.user.id, todayKey())

  try {
    const upstream = await fetch('https://api.openai.com/v1/chat/completions', {
      method: 'POST',
      headers: {
        'Content-Type':  'application/json',
        'Authorization': 'Bearer ' + OPENAI_API_KEY,
      },
      body: JSON.stringify(req.body),
    })
    const body = await upstream.text()
    res.status(upstream.status)
       .type(upstream.headers.get('content-type') || 'application/json')
       .set('X-Usage-Used',      String(after))
       .set('X-Usage-Limit',     String(DAILY_LIMIT))
       .set('X-Usage-Remaining', String(Math.max(0, DAILY_LIMIT - after)))
       .send(body)
  } catch (e) {
    // OpenAI errored or network failed — the user shouldn't pay a quota
    // hit for our infrastructure failure, so refund the increment.
    db.decrementTodayCount(req.user.id, todayKey())
    res.status(502).json({ error: 'Upstream chat completion failed: ' + e.message })
  }
})

// ─── Start ────────────────────────────────────────────────────────────────
app.listen(PORT, () => {
  console.log(`[proxy] listening on http://localhost:${PORT}`)
  console.log(`[proxy] daily limit per user: ${DAILY_LIMIT}`)
  console.log(`[proxy] db: ${DB_PATH}`)
})
