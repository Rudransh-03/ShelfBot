// ShelfBot proxy server.
//
// Routes:
//   GET    /health                       — liveness
//   POST   /device/register {deviceId}   — idempotent device registration
//                                          returns JWT scoped to this device
//   GET    /me                           — device info + today's usage
//   POST   /proxy/embeddings             — auth'd passthrough to OpenAI
//   POST   /proxy/chat/completions       — auth'd passthrough; counts
//                                          against the device's daily cap
//
// The desktop app never sees the OpenAI key — that's the entire point.
// Identity is the device, not an email; rate limits are per-device-per-day.

import dotenv  from 'dotenv'
import express from 'express'
import cors    from 'cors'

import { openDb, todayKey } from './db.js'
import { makeAuth }         from './auth.js'

dotenv.config()

const PORT              = parseInt(process.env.PORT || '8787', 10)
const JWT_SECRET        = process.env.JWT_SECRET
const JWT_TTL_SECONDS   = parseInt(process.env.JWT_TTL_SECONDS || '2592000', 10)
const FREE_DAILY        = parseInt(process.env.FREE_DAILY || '5', 10)
const PRO_DAILY         = parseInt(process.env.PRO_DAILY  || '25', 10)
const OPENAI_API_KEY    = process.env.OPENAI_API_KEY
const OPENAI_BASE_URL   = (process.env.OPENAI_BASE_URL || 'https://api.openai.com/v1').replace(/\/$/, '')
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

/** Returns the daily quota for a given plan. Adding tiers later = adding one line. */
function planLimit(plan) {
  if (plan === 'pro') return PRO_DAILY
  return FREE_DAILY
}

const app = express()
app.use(cors())
app.use(express.json({ limit: '8mb' }))

// ─── Health ───────────────────────────────────────────────────────────────
app.get('/health', (req, res) => {
  res.json({
    ok:       true,
    service:  'shelfbot-proxy',
    plans:    { free: FREE_DAILY, pro: PRO_DAILY },
  })
})

// ─── Device registration ──────────────────────────────────────────────────
//
// Called automatically by the Electron app on first launch. Subsequent
// launches reuse the saved JWT (and only re-register if the saved token
// stops verifying). Idempotent — replays from the same device just refresh
// last_seen and return a fresh JWT.

app.post('/device/register', (req, res) => {
  const deviceId = (req.body?.deviceId || '').trim()
  // Validate shape — a real machine fingerprint or UUID, not a 1-char string
  // a curious user typed in to test the API.
  if (deviceId.length < 8 || deviceId.length > 128) {
    return res.status(400).json({ error: 'deviceId must be 8–128 characters' })
  }

  const { token, device, expiresIn } = auth.registerDevice(deviceId)
  res.json({
    token,
    expiresIn,
    device: { id: device.id, deviceId: device.device_id, plan: device.plan },
    usage:  {
      used:      db.getTodayCount(device.id, todayKey()),
      limit:     planLimit(device.plan),
      remaining: Math.max(0, planLimit(device.plan) - db.getTodayCount(device.id, todayKey())),
    },
  })
})

// ─── Current device info + usage ──────────────────────────────────────────
app.get('/me', auth.requireAuth, (req, res) => {
  const limit = planLimit(req.device.plan)
  const used  = db.getTodayCount(req.device.id, todayKey())
  res.json({
    device: { id: req.device.id, deviceId: req.device.device_id, plan: req.device.plan },
    usage:  { used, limit, remaining: Math.max(0, limit - used) },
  })
})

// ─── OpenAI passthrough ───────────────────────────────────────────────────
//
// Embeddings are NOT rate-limited. (They're bounded by the indexer and the
// per-file/total token caps already enforced inside the app; the user
// can't fire them on demand.) Chat completions are the only billable
// surface they control, so that's what counts against the daily cap.

app.post('/proxy/embeddings', auth.requireAuth, async (req, res) => {
  try {
    const upstream = await fetch(OPENAI_BASE_URL + '/embeddings', {
      method:  'POST',
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
  const limit = planLimit(req.device.plan)
  const used  = db.getTodayCount(req.device.id, todayKey())
  if (used >= limit) {
    return res.status(429).json({
      error:     'Daily query limit reached.',
      plan:      req.device.plan,
      used,
      limit,
      remaining: 0,
      upgradeHint: req.device.plan === 'free'
        ? 'Upgrade to Pro for ' + PRO_DAILY + ' queries/day.'
        : null,
    })
  }

  const after = db.incrementTodayCount(req.device.id, todayKey())

  try {
    const upstream = await fetch(OPENAI_BASE_URL + '/chat/completions', {
      method:  'POST',
      headers: {
        'Content-Type':  'application/json',
        'Authorization': 'Bearer ' + OPENAI_API_KEY,
      },
      body: JSON.stringify(req.body),
    })
    const body = await upstream.text()
    res.status(upstream.status)
       .type(upstream.headers.get('content-type') || 'application/json')
       .set('X-Plan',            req.device.plan)
       .set('X-Usage-Used',      String(after))
       .set('X-Usage-Limit',     String(limit))
       .set('X-Usage-Remaining', String(Math.max(0, limit - after)))
       .send(body)
  } catch (e) {
    // Refund the slot — the user shouldn't pay for our outage.
    db.decrementTodayCount(req.device.id, todayKey())
    res.status(502).json({ error: 'Upstream chat completion failed: ' + e.message })
  }
})

// ─── Start ────────────────────────────────────────────────────────────────
app.listen(PORT, () => {
  console.log(`[proxy] listening on http://localhost:${PORT}`)
  console.log(`[proxy] plans:  free=${FREE_DAILY}/day  pro=${PRO_DAILY}/day`)
  console.log(`[proxy] db:     ${DB_PATH}`)
})

export { app, db, planLimit } // exported for tests
