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

import dotenv     from 'dotenv'
import express    from 'express'
import cors       from 'cors'
import { randomUUID } from 'node:crypto'

import { openDb, todayKey }          from './db.js'
import { makeAuth }                  from './auth.js'
import { verifyGoogleCode, isStubMode } from './google.js'

dotenv.config()

const PORT                  = parseInt(process.env.PORT || '8787', 10)
const JWT_SECRET            = process.env.JWT_SECRET
const JWT_TTL_SECONDS       = parseInt(process.env.JWT_TTL_SECONDS || '2592000', 10)
const FREE_DAILY            = parseInt(process.env.FREE_DAILY || '5', 10)
const PRO_DAILY             = parseInt(process.env.PRO_DAILY  || '25', 10)
// TESTING: bumped to 20/20 for now. Restore to 1 (free) / 5 (pro) before shipping.
const FREE_REORG_DAILY      = parseInt(process.env.FREE_REORG_DAILY || '20', 10)
const PRO_REORG_DAILY       = parseInt(process.env.PRO_REORG_DAILY  || '20', 10)
const REORG_LLM_BUDGET      = parseInt(process.env.REORG_LLM_BUDGET || '50', 10)
const REORG_SESSION_TTL_MIN = parseInt(process.env.REORG_SESSION_TTL_MIN || '30', 10)
const OPENAI_API_KEY        = process.env.OPENAI_API_KEY
const OPENAI_BASE_URL       = (process.env.OPENAI_BASE_URL || 'https://api.openai.com/v1').replace(/\/$/, '')
const DB_PATH               = process.env.DB_PATH || './shelfbot-proxy.db'

// ── Accounts / trial (Google sign-in) ───────────────────────────────────────
// A signed-in account on the free TRIAL gets TRIAL_DAILY chats/day for
// TRIAL_WINDOW_HOURS after first sign-in, chat only (no reorg/deadlines). After
// that they must upgrade to 'pro'. Google credentials are only needed for the
// real sign-in exchange (tests use the GOOGLE_AUTH_STUB seam in google.js).
const TRIAL_DAILY          = parseInt(process.env.TRIAL_DAILY || '6', 10)
const TRIAL_WINDOW_HOURS   = parseInt(process.env.TRIAL_WINDOW_HOURS || '24', 10)
const GOOGLE_CLIENT_ID     = process.env.GOOGLE_CLIENT_ID || ''
const GOOGLE_CLIENT_SECRET = process.env.GOOGLE_CLIENT_SECRET || ''

// Accounts that always get the 'pro' plan (founder / team / comped testers),
// regardless of trial state — no payment needed. The founder is hardcoded so it
// works out of the box; add more via PRO_EMAILS (comma-separated) on the proxy.
const PRO_EMAILS = new Set(
  ['rudranshtyagi2002@gmail.com', ...(process.env.PRO_EMAILS || '').split(',')]
    .map(s => s.trim().toLowerCase()).filter(Boolean)
)

// Cost guardrails. The desktop app only ever needs these cheap models, but the
// device JWT is easy to obtain (registration is open by design) and the body is
// client-supplied — so a tampered/hostile client could otherwise ask us to bill
// an expensive model (gpt-4o, o1, …) or an enormous completion against OUR key.
// We pin the model to an allowlist and clamp max_tokens server-side. Override
// via env if the app's model choice ever changes.
const CHAT_MODELS_ALLOWED = new Set(
  (process.env.CHAT_MODELS_ALLOWED || 'gpt-4o-mini').split(',').map(s => s.trim()).filter(Boolean)
)
const EMBED_MODELS_ALLOWED = new Set(
  (process.env.EMBED_MODELS_ALLOWED || 'text-embedding-3-small,text-embedding-3-large').split(',').map(s => s.trim()).filter(Boolean)
)
const MAX_COMPLETION_TOKENS = parseInt(process.env.MAX_COMPLETION_TOKENS || '4096', 10)

// ── Abuse throttle (Layer 2) ────────────────────────────────────────────────
// Device registration is anonymous, so a script can mint unlimited devices —
// each carrying a fresh free quota — and use us as a free OpenAI relay. Until
// usage is license-gated, we blunt bulk farming with a generous per-IP, per-day
// cap on the two anonymous-cost surfaces: registration and the OpenAI-forwarding
// /proxy/* routes. Caps are deliberately high (whole offices / carrier-grade NAT
// sit behind one IP) — high enough to never hit a real network, low enough that
// a curl loop dies fast. 0 disables a limiter.
const REGISTER_IP_DAILY = parseInt(process.env.REGISTER_IP_DAILY || '20', 10)
const PROXY_IP_DAILY    = parseInt(process.env.PROXY_IP_DAILY    || '200', 10)
// Number of reverse-proxy hops to trust for the client IP. MUST be 1 (or your
// real hop count) in production behind nginx/Cloud Run, else every request looks
// like it comes from the load balancer and all users share one bucket. 0 (the
// dev default) uses the direct socket IP.
const TRUST_PROXY = parseInt(process.env.TRUST_PROXY || '0', 10)

/**
 * Validates + clamps a chat-completions body before it goes upstream on our key.
 * Returns { body } on success or { error } (a 400 message) on rejection.
 */
function sanitizeChatBody(raw) {
  if (!raw || typeof raw !== 'object') return { error: 'Request body must be a JSON object.' }
  const model = raw.model
  if (typeof model !== 'string' || !CHAT_MODELS_ALLOWED.has(model)) {
    return { error: 'Unsupported model.' }
  }
  const body = { ...raw }
  // Clamp the caller's requested completion size to our ceiling (and only ever
  // shrink it — never raise what the client asked for).
  if (typeof body.max_tokens === 'number') {
    body.max_tokens = Math.min(body.max_tokens, MAX_COMPLETION_TOKENS)
  } else {
    body.max_tokens = MAX_COMPLETION_TOKENS
  }
  return { body }
}

if (!JWT_SECRET || JWT_SECRET.length < 16) {
  console.error('[proxy] JWT_SECRET missing or too short. Refusing to start.')
  process.exit(1)
}
if (!OPENAI_API_KEY) {
  console.error('[proxy] OPENAI_API_KEY missing. Refusing to start.')
  process.exit(1)
}

const db   = openDb(DB_PATH)
const auth = makeAuth({ db, jwtSecret: JWT_SECRET, jwtTtlSeconds: JWT_TTL_SECONDS, proEmails: PRO_EMAILS })

/** Returns the daily quota for a given plan. Adding tiers later = adding one line. */
function planLimit(plan) {
  if (plan === 'pro') return PRO_DAILY
  return FREE_DAILY
}

/** Returns the per-day reorg-start quota for a given plan. */
function reorgPlanLimit(plan) {
  if (plan === 'pro') return PRO_REORG_DAILY
  return FREE_REORG_DAILY
}

// ── Principal dispatch (account vs legacy device) ───────────────────────────
// req.principal.kind is 'account' (Google sign-in) or 'device' (legacy
// anonymous). These helpers route the daily chat counter + limit to the right
// table/plan so the chat route logic stays single-path.

/** Daily chat limit for a principal. */
function chatLimitFor(p) {
  if (p.kind === 'account') return p.plan === 'pro' ? PRO_DAILY : TRIAL_DAILY
  return planLimit(p.plan)
}
/** Today's chat count for a principal. */
function chatUsedFor(p, day) {
  return p.kind === 'account' ? db.getAccountChatToday(p.id, day) : db.getTodayCount(p.id, day)
}
/** Atomically increments + returns the principal's chat count. */
function chatIncFor(p, day) {
  return p.kind === 'account' ? db.incrementAccountChatToday(p.id, day) : db.incrementTodayCount(p.id, day)
}
/** Refunds a chat increment for a principal (upstream failure). */
function chatDecFor(p, day) {
  return p.kind === 'account' ? db.decrementAccountChatToday(p.id, day) : db.decrementTodayCount(p.id, day)
}

/**
 * Trial/access state for an account. Pro accounts are always active. A trial
 * account is active only within TRIAL_WINDOW_HOURS of first sign-in; after that
 * it's expired and must upgrade.
 */
function trialState(account) {
  if (account.plan === 'pro') return { active: true, expired: false }
  const start   = Date.parse(account.trial_started_at)
  const expired = Number.isFinite(start) && Date.now() > start + TRIAL_WINDOW_HOURS * 3600 * 1000
  return { active: !expired, expired }
}

/** The account fields safe to return to the client. */
function publicAccount(a) {
  return { id: a.id, email: a.email, plan: a.plan }
}

/** Upgrade nudge for a quota-exhausted principal (null when already pro). */
function upgradeHintFor(p) {
  if (p.plan === 'pro') return null
  return 'Upgrade to Pro for ' + PRO_DAILY + ' queries/day.'
}

const app = express()
// Honor X-Forwarded-For only for the configured number of trusted hops, so
// req.ip is the real client behind a reverse proxy without letting a caller
// spoof their IP via a forged header to dodge the rate limiter.
app.set('trust proxy', TRUST_PROXY)
app.use(cors())
app.use(express.json({ limit: '8mb' }))

// In-memory per-IP daily counters. The proxy is single-instance, so this is
// enough; the whole map is dropped when the UTC day rolls over (self-pruning,
// no unbounded growth) and on restart (acceptable for a stopgap throttle).
let rlDay = todayKey()
const ipHits = new Map()
function rateLimit(bucket, max) {
  return (req, res, next) => {
    if (max <= 0) return next()             // limiter disabled
    const day = todayKey()
    if (day !== rlDay) { ipHits.clear(); rlDay = day }
    const key = `${bucket}:${req.ip || 'unknown'}`
    const n = (ipHits.get(key) || 0) + 1
    ipHits.set(key, n)
    if (n > max) {
      return res.status(429).json({
        error: 'Too many requests from your network today. Please try again tomorrow.',
      })
    }
    next()
  }
}
const limitRegister = rateLimit('register', REGISTER_IP_DAILY)
const limitProxy    = rateLimit('proxy',    PROXY_IP_DAILY)

// ─── Health ───────────────────────────────────────────────────────────────
app.get('/health', (req, res) => {
  res.json({
    ok:       true,
    service:  'shelfbot-proxy',
    plans:    { free: FREE_DAILY, pro: PRO_DAILY },
    reorg:    {
      free:       FREE_REORG_DAILY,
      pro:        PRO_REORG_DAILY,
      llmBudget:  REORG_LLM_BUDGET,
      sessionTtlMin: REORG_SESSION_TTL_MIN,
    },
  })
})

// ─── Device registration ──────────────────────────────────────────────────
//
// Called automatically by the Electron app on first launch. Subsequent
// launches reuse the saved JWT (and only re-register if the saved token
// stops verifying). Idempotent — replays from the same device just refresh
// last_seen and return a fresh JWT.

app.post('/device/register', limitRegister, (req, res) => {
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

// ─── Google sign-in ─────────────────────────────────────────────────────────
//
// The desktop app runs the PKCE browser flow, captures the authorization `code`
// on its 127.0.0.1 loopback redirect, and POSTs {code, codeVerifier,
// redirectUri} here. We verify it with Google (secret stays server-side),
// find-or-create the account (first sign-in starts the 24h trial), and return a
// JWT bound to the account. Reuses the registration IP throttle.
app.post('/auth/google', limitRegister, async (req, res) => {
  const code         = (req.body?.code || '').trim()
  const codeVerifier = (req.body?.codeVerifier || '').trim()
  const redirectUri  = (req.body?.redirectUri || '').trim()
  if (!code || !codeVerifier || !redirectUri) {
    return res.status(400).json({ error: 'code, codeVerifier and redirectUri are required' })
  }

  let profile
  try {
    profile = await verifyGoogleCode({
      code, codeVerifier, redirectUri,
      clientId: GOOGLE_CLIENT_ID, clientSecret: GOOGLE_CLIENT_SECRET,
    })
  } catch (e) {
    return res.status(401).json({ error: 'Google sign-in failed: ' + e.message })
  }
  if (!profile?.email || !profile.emailVerified) {
    return res.status(403).json({ error: 'A verified Google email is required to sign in.' })
  }

  const account = db.upsertAccountByGoogle({ sub: profile.sub, email: profile.email.toLowerCase() })
  // Founder/team allowlist → persist 'pro' so it sticks across sessions (the
  // requireAuth override also applies it live on every request).
  if (PRO_EMAILS.has(account.email) && account.plan !== 'pro') {
    db.setAccountPlan(account.id, 'pro')
    account.plan = 'pro'
  }
  const { token, expiresIn } = auth.issueAccountToken(account)

  const day   = todayKey()
  const limit = chatLimitFor({ kind: 'account', plan: account.plan })
  const used  = db.getAccountChatToday(account.id, day)
  const ts    = trialState(account)
  res.json({
    token, expiresIn,
    account: publicAccount(account),
    trial:   { active: ts.active, expired: ts.expired, windowHours: TRIAL_WINDOW_HOURS },
    usage:   { used, limit, remaining: Math.max(0, limit - used) },
  })
})

// ─── Current identity info + usage ──────────────────────────────────────────
app.get('/me', auth.requireAuth, (req, res) => {
  const p     = req.principal
  const limit = chatLimitFor(p)
  const used  = chatUsedFor(p, todayKey())
  const usage = { used, limit, remaining: Math.max(0, limit - used) }

  if (p.kind === 'account') {
    const ts = trialState(p.account)
    return res.json({
      account: publicAccount(p.account),
      plan:    p.plan,
      trial:   { active: ts.active, expired: ts.expired, windowHours: TRIAL_WINDOW_HOURS,
                 startedAt: p.account.trial_started_at },
      usage,
    })
  }
  // Legacy device identity (unchanged shape).
  res.json({
    device: { id: p.device.id, deviceId: p.device.device_id, plan: p.device.plan },
    usage,
  })
})

// ─── OpenAI passthrough ───────────────────────────────────────────────────
//
// Embeddings are NOT rate-limited. (They're bounded by the indexer and the
// per-file/total token caps already enforced inside the app; the user
// can't fire them on demand.) Chat completions are the only billable
// surface they control, so that's what counts against the daily cap.

app.post('/proxy/embeddings', limitProxy, auth.requireAuth, async (req, res) => {
  if (typeof req.body?.model !== 'string' || !EMBED_MODELS_ALLOWED.has(req.body.model)) {
    return res.status(400).json({ error: 'Unsupported embedding model.' })
  }
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

app.post('/proxy/chat/completions', limitProxy, auth.requireAuth, async (req, res) => {
  const clean = sanitizeChatBody(req.body)
  if (clean.error) return res.status(400).json({ error: clean.error })
  req.body = clean.body

  const p   = req.principal
  const day = todayKey()

  // Account trial gating: once the trial window lapses, chat is blocked until
  // upgrade (pro accounts are always active; device tokens skip this entirely).
  if (p.kind === 'account') {
    const ts = trialState(p.account)
    if (!ts.active) {
      return res.status(402).json({
        error:       'Your free trial has ended.',
        reason:      'trial_ended',
        plan:        p.plan,
        upgradeHint: 'Upgrade to keep chatting with your documents.',
      })
    }
  }

  const limit = chatLimitFor(p)
  const used  = chatUsedFor(p, day)
  if (used >= limit) {
    return res.status(429).json({
      error:     'Daily query limit reached.',
      plan:      p.plan,
      used,
      limit,
      remaining: 0,
      upgradeHint: upgradeHintFor(p),
    })
  }

  const after = chatIncFor(p, day)

  // Streaming branch: when the client sets {"stream": true}, OpenAI returns
  // an SSE response. We pipe its bytes straight through to the client so
  // each delta token shows up live in the chat UI. Same auth + rate-limit
  // logic; only the response shape differs.
  const wantsStream = req.body && req.body.stream === true

  try {
    const upstream = await fetch(OPENAI_BASE_URL + '/chat/completions', {
      method:  'POST',
      headers: {
        'Content-Type':  'application/json',
        'Authorization': 'Bearer ' + OPENAI_API_KEY,
      },
      body: JSON.stringify(req.body),
    })

    // Upstream rejected the call (OpenAI rate-limit, 5xx, malformed request…).
    // The user got no answer, so refund the slot — same contract as the reorg
    // path below — and report the corrected usage. Without this, an OpenAI-side
    // hiccup silently burns one of the user's daily queries AND surfaces to the
    // desktop app as a misleading "daily limit reached" 429.
    if (!upstream.ok) {
      const refunded = chatDecFor(p, day)
      const body = await upstream.text()
      return res.status(upstream.status)
         .set('X-Plan',            p.plan)
         .set('X-Usage-Used',      String(refunded))
         .set('X-Usage-Limit',     String(limit))
         .set('X-Usage-Remaining', String(Math.max(0, limit - refunded)))
         .type(upstream.headers.get('content-type') || 'application/json')
         .send(body)
    }

    // Mirror useful response metadata (success path).
    res.status(upstream.status)
       .set('X-Plan',            p.plan)
       .set('X-Usage-Used',      String(after))
       .set('X-Usage-Limit',     String(limit))
       .set('X-Usage-Remaining', String(Math.max(0, limit - after)))

    if (wantsStream && upstream.body) {
      // Pipe SSE bytes straight through. We DO NOT buffer — that would
      // defeat the entire point of streaming. Set headers explicitly so
      // intermediaries (and Express) don't try to gzip / chunk-buffer.
      res.set('Content-Type',      'text/event-stream')
      res.set('Cache-Control',     'no-cache, no-transform')
      res.set('Connection',        'keep-alive')
      res.set('X-Accel-Buffering', 'no')          // disable nginx buffering if behind one
      res.flushHeaders?.()

      // Node 18+ fetch returns a web ReadableStream; use the standard
      // Response.body.pipeTo + writable stream, or fall back to async iteration.
      try {
        for await (const chunk of upstream.body) {
          if (!res.write(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk))) {
            // backpressure
            await new Promise(resolve => res.once('drain', resolve))
          }
        }
      } catch (streamErr) {
        // Mid-stream failure — best-effort terminate and refund.
        console.warn('[proxy] stream pipe error:', streamErr.message)
      }
      res.end()
      return
    }

    // Non-streaming path (legacy): pull full body, send.
    const body = await upstream.text()
    res.type(upstream.headers.get('content-type') || 'application/json').send(body)
  } catch (e) {
    // Refund the slot — the user shouldn't pay for our outage.
    chatDecFor(p, day)
    res.status(502).json({ error: 'Upstream chat completion failed: ' + e.message })
  }
})

// ─── Reorg sessions ───────────────────────────────────────────────────────
//
// A reorg is a multi-call interaction: the backend calls /reorg/llm many
// times for naming clusters and judging loners. We gate it on two axes:
//
//   1. Per-day starts  — free 1/day, pro 5/day. Prevents the user from
//      kicking off unlimited reorgs even if the per-session budget held.
//   2. Per-session LLM-call budget (default 50) — prevents a single reorg
//      from runaway-burning credits if the backend or LLM loops.
//
// /reorg/start creates a session, decrements the day cap. /reorg/llm draws
// from the session's budget, refunding on upstream failure. The session ID
// is opaque to the backend and bound to the device that opened it.

app.post('/reorg/start', limitProxy, auth.requireAuth, (req, res) => {
  // Reorg is not part of the chat-only trial. (Per-account reorg quota lands
  // with the paid plan / billing webhook; legacy device tokens still work.)
  if (req.principal.kind === 'account') {
    return res.status(403).json({
      error:  'Reorganize isn’t part of the trial.',
      reason: 'reorg_not_in_trial',
      detail: 'Folder organization will be available on a paid plan.',
    })
  }

  const day   = todayKey()
  const limit = reorgPlanLimit(req.device.plan)
  const used  = db.getReorgStartsToday(req.device.id, day)

  if (used >= limit) {
    return res.status(429).json({
      error:       'Daily reorganization limit reached for this device.',
      detail:      `You've used ${used} of ${limit} reorganizations today. The limit resets at midnight UTC.`,
      plan:        req.device.plan,
      used,
      limit,
      remaining:   0,
      upgradeHint: req.device.plan === 'free'
        ? `Upgrade to Pro for ${PRO_REORG_DAILY} reorganizations/day.`
        : null,
    })
  }

  const after = db.incrementReorgStartsToday(req.device.id, day)

  const sessionId = randomUUID()
  const expiresAt = new Date(Date.now() + REORG_SESSION_TTL_MIN * 60 * 1000).toISOString()
  try {
    db.createReorgSession(sessionId, req.device.id, REORG_LLM_BUDGET, expiresAt)
  } catch (e) {
    // Roll back the day-cap decrement so the user isn't charged for our error.
    db.decrementReorgStartsToday(req.device.id, day)
    return res.status(500).json({ error: 'Failed to create reorg session: ' + e.message })
  }

  res.json({
    sessionId,
    llmBudget:    REORG_LLM_BUDGET,
    expiresAt,
    plan:         req.device.plan,
    usage:        { used: after, limit, remaining: Math.max(0, limit - after) },
  })
})

app.post('/reorg/llm', limitProxy, auth.requireAuth, async (req, res) => {
  if (req.principal.kind === 'account') {
    return res.status(403).json({
      error:  'Reorganize isn’t part of the trial.',
      reason: 'reorg_not_in_trial',
    })
  }

  const sessionId = (req.body?.sessionId || '').trim()
  if (!sessionId) {
    return res.status(400).json({ error: 'Missing sessionId in request body.' })
  }

  const now = new Date().toISOString()
  const session = db.getActiveReorgSession(sessionId, now)
  if (!session) {
    return res.status(404).json({
      error:  'Reorg session not found or expired.',
      detail: 'Start a new reorganization to get a fresh session.',
    })
  }
  if (session.device_id !== req.device.id) {
    // Session belongs to a different device. Treat as not-found to avoid
    // leaking the existence of other devices' sessions.
    return res.status(404).json({
      error:  'Reorg session not found or expired.',
      detail: 'Start a new reorganization to get a fresh session.',
    })
  }

  // Same model/size guardrails as the chat path — this also forwards to OpenAI
  // on our key. Reject (before spending budget) anything off the allowlist.
  const cleanReorg = sanitizeChatBody({ ...req.body, sessionId: undefined })
  if (cleanReorg.error) return res.status(400).json({ error: cleanReorg.error })

  const remaining = db.decrementReorgSessionBudget(sessionId, now)
  if (remaining < 0) {
    return res.status(429).json({
      error:  'This reorganization scope is too large to finish — LLM budget exhausted.',
      detail: `I'm only allowed ${REORG_LLM_BUDGET} small decisions per reorganization to keep costs and quality in check. Try running on a smaller subfolder instead.`,
      budget: { remaining: 0, initial: session.budget_initial },
    })
  }

  // Use the validated + clamped body (model pinned, max_tokens capped). The
  // sessionId was stripped inside sanitizeChatBody's input already.
  const upstreamBody = cleanReorg.body
  delete upstreamBody.sessionId   // belt-and-suspenders: never leak it upstream

  try {
    const upstream = await fetch(OPENAI_BASE_URL + '/chat/completions', {
      method:  'POST',
      headers: {
        'Content-Type':  'application/json',
        'Authorization': 'Bearer ' + OPENAI_API_KEY,
      },
      body: JSON.stringify(upstreamBody),
    })

    res.status(upstream.status)
       .set('X-Reorg-Budget-Remaining', String(remaining))
       .set('X-Reorg-Budget-Initial',   String(session.budget_initial))

    if (!upstream.ok) {
      // Upstream rejected the call (rate-limited, bad request, etc.). Refund
      // the budget — the model didn't actually run for us.
      db.refundReorgSessionBudget(sessionId)
    }

    const body = await upstream.text()
    res.type(upstream.headers.get('content-type') || 'application/json').send(body)
  } catch (e) {
    // Network / connection failure. Refund.
    db.refundReorgSessionBudget(sessionId)
    res.status(502).json({ error: 'Upstream LLM call failed: ' + e.message })
  }
})

// ─── Crash safety ───────────────────────────────────────────────────────────
// A long-running service must not be taken down for every user by one stray
// async error. We log unhandled rejections and keep serving; an uncaught
// exception leaves the process in an undefined state, so we log and exit so the
// process manager (systemd / pm2 / Cloud Run) restarts a clean instance.
process.on('unhandledRejection', (reason) => {
  console.error('[proxy] unhandledRejection:', reason)
})
process.on('uncaughtException', (err) => {
  console.error('[proxy] uncaughtException — exiting for a clean restart:', err)
  process.exit(1)
})

// ─── Start ────────────────────────────────────────────────────────────────
app.listen(PORT, () => {
  console.log(`[proxy] listening on http://localhost:${PORT}`)
  console.log(`[proxy] plans:  free=${FREE_DAILY}/day  pro=${PRO_DAILY}/day`)
  console.log(`[proxy] trial:  ${TRIAL_DAILY} chats/day for ${TRIAL_WINDOW_HOURS}h (Google sign-in)`)
  console.log(`[proxy] reorg:  free=${FREE_REORG_DAILY}/day  pro=${PRO_REORG_DAILY}/day  budget=${REORG_LLM_BUDGET}/session`)
  console.log(`[proxy] google: ${GOOGLE_CLIENT_ID ? 'configured' : 'NOT configured (sign-in will fail until GOOGLE_CLIENT_ID/SECRET are set)'}`)
  if (isStubMode()) {
    console.warn('[proxy] ⚠️  GOOGLE_AUTH_STUB=1 — Google verification is STUBBED. For tests only. NEVER set this in production.')
  }
  console.log(`[proxy] db:     ${DB_PATH}`)
})

export { app, db, planLimit, reorgPlanLimit } // exported for tests
