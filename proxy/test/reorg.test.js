// Tests for the reorg gates: /reorg/start daily cap, /reorg/llm session
// budget, device isolation, and refund behaviour on upstream failures.
//
// We spin up a fresh proxy + OpenAI stub for this suite so we can flip
// the stub between "200 OK" and "5xx" mid-test to validate refunds. The
// FREE_REORG_DAILY=2 / PRO=4 caps are small so we can exhaust them
// cheaply.

import test     from 'node:test'
import assert   from 'node:assert/strict'
import http     from 'node:http'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir }              from 'node:os'
import { join }                from 'node:path'
import { spawn }               from 'node:child_process'

let openaiStub, openaiUrl
let proxy,      proxyUrl
let tmpDir
let stubBehavior = 'ok'    // toggleable: 'ok' | '500'

// ── Test bootstrap ────────────────────────────────────────────────────────

test.before(async () => {
  // OpenAI stub — behaviour toggles via the shared `stubBehavior` var so
  // tests can simulate transient upstream failures without restarting.
  await new Promise((resolve) => {
    openaiStub = http.createServer((req, res) => {
      if (stubBehavior === '500') {
        res.writeHead(500, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ error: 'simulated upstream failure' }))
        return
      }
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({
        id: 'stub', object: 'chat.completion', choices: [
          { message: { role: 'assistant', content: '{"name":"Tax"}' }, finish_reason: 'stop', index: 0 }
        ]
      }))
    })
    openaiStub.listen(0, '127.0.0.1', () => {
      openaiUrl = `http://127.0.0.1:${openaiStub.address().port}`
      resolve()
    })
  })

  tmpDir = mkdtempSync(join(tmpdir(), 'shelfbot-reorg-test-'))
  const proxyPort = 19000 + Math.floor(Math.random() * 1000)
  proxyUrl = `http://127.0.0.1:${proxyPort}`

  proxy = spawn(process.execPath, ['src/server.js'], {
    env: {
      ...process.env,
      OPENAI_API_KEY:        'sk-stub',
      OPENAI_BASE_URL:       openaiUrl,
      JWT_SECRET:            'a'.repeat(32),
      FREE_DAILY:            '99',          // not under test here
      PRO_DAILY:             '99',
      FREE_REORG_DAILY:      '2',           // small so we exhaust easily
      PRO_REORG_DAILY:       '4',
      REORG_LLM_BUDGET:      '3',           // small budget for budget tests
      REORG_SESSION_TTL_MIN: '30',
      PORT:                  String(proxyPort),
      DB_PATH:               join(tmpDir, 'test.db'),
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })

  await new Promise((resolve, reject) => {
    let buf = ''
    const timer = setTimeout(() => reject(new Error('proxy did not start: ' + buf)), 8000)
    proxy.stdout.on('data', (d) => {
      buf += d.toString()
      if (buf.includes('listening')) { clearTimeout(timer); resolve() }
    })
    proxy.stderr.on('data', (d) => { buf += '[err] ' + d.toString() })
    proxy.on('exit', (code) => {
      if (code !== null && code !== 0) {
        clearTimeout(timer)
        reject(new Error(`proxy exited early (code ${code}): ${buf}`))
      }
    })
  })
})

test.after(async () => {
  if (proxy)      { proxy.kill('SIGTERM');     await new Promise(r => proxy.once('exit', r)) }
  if (openaiStub) { openaiStub.close() }
  if (tmpDir)     { rmSync(tmpDir, { recursive: true, force: true }) }
})

// ── Helpers ──────────────────────────────────────────────────────────────

async function register(deviceId) {
  const r = await fetch(`${proxyUrl}/device/register`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify({ deviceId }),
  })
  return r.json()
}

async function startReorg(token) {
  const r = await fetch(`${proxyUrl}/reorg/start`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body:    JSON.stringify({}),
  })
  return { status: r.status, body: await r.json() }
}

async function reorgLlm(token, sessionId, extra = {}) {
  const r = await fetch(`${proxyUrl}/reorg/llm`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body:    JSON.stringify({
      sessionId,
      model: 'gpt-4o-mini',
      messages: [{ role: 'user', content: 'name this cluster' }],
      ...extra,
    }),
  })
  const text = await r.text()
  return {
    status:    r.status,
    body:      text ? JSON.parse(text) : {},
    remaining: r.headers.get('x-reorg-budget-remaining'),
  }
}

// ── Health ────────────────────────────────────────────────────────────────

test('/health exposes reorg caps', async () => {
  const r    = await fetch(`${proxyUrl}/health`)
  const body = await r.json()
  assert.equal(body.reorg.free, 2)
  assert.equal(body.reorg.pro, 4)
  assert.equal(body.reorg.llmBudget, 3)
})

// ── /reorg/start ──────────────────────────────────────────────────────────

test('/reorg/start returns sessionId + budget on first call', async () => {
  const reg = await register('reorg-start-happy-12345')
  const r   = await startReorg(reg.token)
  assert.equal(r.status, 200)
  assert.ok(r.body.sessionId, 'sessionId returned')
  assert.equal(r.body.llmBudget, 3)
  assert.equal(r.body.usage.used, 1)
  assert.equal(r.body.usage.limit, 2)
  assert.equal(r.body.usage.remaining, 1)
})

test('/reorg/start blocks at daily cap with friendly 429', async () => {
  const reg = await register('reorg-start-cap-67890')
  await startReorg(reg.token)                   // 1
  await startReorg(reg.token)                   // 2 (== FREE_REORG_DAILY)
  const r3 = await startReorg(reg.token)        // 3 — blocked

  assert.equal(r3.status, 429)
  assert.equal(r3.body.used, 2)
  assert.equal(r3.body.limit, 2)
  assert.equal(r3.body.remaining, 0)
  // Polite, actionable message
  assert.match(r3.body.error, /Daily reorganization limit/)
  assert.match(r3.body.detail, /midnight UTC/)
  assert.match(r3.body.upgradeHint, /Upgrade to Pro/)
})

test('/reorg/start requires auth', async () => {
  const r = await fetch(`${proxyUrl}/reorg/start`, { method: 'POST' })
  assert.equal(r.status, 401)
})

// ── /reorg/llm session budget ─────────────────────────────────────────────

test('/reorg/llm decrements budget on each call', async () => {
  const reg     = await register('reorg-llm-budget-AAAAA')
  const session = await startReorg(reg.token)
  const sid     = session.body.sessionId

  const c1 = await reorgLlm(reg.token, sid)
  assert.equal(c1.status, 200, 'call 1 OK')
  assert.equal(c1.remaining, '2', 'budget 3 → 2')

  const c2 = await reorgLlm(reg.token, sid)
  assert.equal(c2.status, 200)
  assert.equal(c2.remaining, '1')

  const c3 = await reorgLlm(reg.token, sid)
  assert.equal(c3.status, 200)
  assert.equal(c3.remaining, '0', 'last call within budget')
})

test('/reorg/llm returns 429 with friendly message when budget exhausted', async () => {
  const reg     = await register('reorg-llm-exhaust-BBBBB')
  const session = await startReorg(reg.token)
  const sid     = session.body.sessionId

  // Drain the budget (3 calls)
  await reorgLlm(reg.token, sid)
  await reorgLlm(reg.token, sid)
  await reorgLlm(reg.token, sid)

  const r = await reorgLlm(reg.token, sid)
  assert.equal(r.status, 429)
  assert.match(r.body.error, /scope is too large/)
  assert.match(r.body.detail, /smaller subfolder/)
  assert.equal(r.body.budget.remaining, 0)
})

test('/reorg/llm requires sessionId', async () => {
  const reg = await register('reorg-llm-no-sid-CCCCC')
  const r = await fetch(`${proxyUrl}/reorg/llm`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${reg.token}` },
    body:    JSON.stringify({ model: 'x', messages: [] }),
  })
  assert.equal(r.status, 400)
})

test('/reorg/llm rejects unknown / bogus sessionId', async () => {
  const reg = await register('reorg-llm-bogus-DDDDD')
  const r   = await reorgLlm(reg.token, '00000000-0000-0000-0000-000000000000')
  assert.equal(r.status, 404)
})

// ── Device isolation ──────────────────────────────────────────────────────

test('one device cannot use another device\'s session', async () => {
  const a = await register('isolation-A-12345-EEEEE')
  const b = await register('isolation-B-67890-FFFFF')

  const session = await startReorg(a.token)
  const sid     = session.body.sessionId

  // Device B presents A's session — must be rejected.
  const r = await reorgLlm(b.token, sid)
  assert.equal(r.status, 404, 'cross-device session reuse must be refused')
})

test('each device has its own daily reorg cap', async () => {
  const a = await register('per-device-cap-A-GGGGG')
  const b = await register('per-device-cap-B-HHHHH')

  // Burn A's cap
  await startReorg(a.token)
  await startReorg(a.token)
  const aBlocked = await startReorg(a.token)
  assert.equal(aBlocked.status, 429)

  // B unaffected
  const bOk = await startReorg(b.token)
  assert.equal(bOk.status, 200)
})

// ── Refund on upstream failure ────────────────────────────────────────────

test('/reorg/llm refunds budget when OpenAI returns 5xx', async () => {
  const reg     = await register('reorg-llm-refund-IIIII')
  const session = await startReorg(reg.token)
  const sid     = session.body.sessionId

  stubBehavior = '500'
  try {
    const r = await reorgLlm(reg.token, sid)
    assert.equal(r.status, 500, 'upstream error propagates')
    // Budget should be refunded — the next call should still succeed
    // with budget 2 remaining (full minus 0 because we refunded).
    stubBehavior = 'ok'
    const r2 = await reorgLlm(reg.token, sid)
    assert.equal(r2.status, 200)
    // Without refund this would be 1 (3 - 2 calls); with refund it's 2 (3 - 1 effective).
    assert.equal(r2.remaining, '2', 'budget should be refunded after upstream failure')
  } finally {
    stubBehavior = 'ok'
  }
})
