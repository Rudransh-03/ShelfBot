// End-to-end tests for the device-identity proxy.
//
// One proxy instance is shared across all tests to keep startup overhead
// from making the suite flaky. Each test uses unique deviceIds so they
// don't collide on the shared DB.
//
// Upstream OpenAI is stubbed via OPENAI_BASE_URL pointing at a local
// HTTP responder that always 200s — keeps the proxy's pass-through path
// deterministic without burning real API credits.

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

// ── Test bootstrap ────────────────────────────────────────────────────────

test.before(async () => {
  // Start the OpenAI stub
  await new Promise((resolve) => {
    openaiStub = http.createServer((req, res) => {
      // Read the request so a test can opt into an upstream failure: a first
      // message of 'fail-upstream' makes the stub return a non-2xx, exercising
      // the proxy's refund-on-upstream-error path. Everything else 200s.
      let body = ''
      req.on('data', (c) => { body += c })
      req.on('end', () => {
        let wantFail = false
        try { wantFail = JSON.parse(body)?.messages?.[0]?.content === 'fail-upstream' } catch {}
        if (wantFail) {
          res.writeHead(500, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ error: { message: 'simulated upstream failure' } }))
          return
        }
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ id: 'stub', object: 'chat.completion', choices: [
          { message: { role: 'assistant', content: 'stub' }, finish_reason: 'stop', index: 0 }
        ]}))
      })
    })
    openaiStub.listen(0, '127.0.0.1', () => {
      openaiUrl = `http://127.0.0.1:${openaiStub.address().port}`
      resolve()
    })
  })

  // Start the proxy
  tmpDir = mkdtempSync(join(tmpdir(), 'shelfbot-proxy-test-'))
  const proxyPort = 18000 + Math.floor(Math.random() * 1000)
  proxyUrl = `http://127.0.0.1:${proxyPort}`

  proxy = spawn(process.execPath, ['src/server.js'], {
    env: {
      ...process.env,
      OPENAI_API_KEY:  'sk-stub',
      OPENAI_BASE_URL: openaiUrl,
      JWT_SECRET:      'a'.repeat(32),
      FREE_DAILY:      '2',
      PRO_DAILY:       '4',
      PORT:            String(proxyPort),
      DB_PATH:         join(tmpDir, 'test.db'),
      // This shared instance isn't testing the IP throttle (a dedicated test
      // below spins up its own proxy for that) — keep caps far above what the
      // whole suite issues from 127.0.0.1 so they never trip here.
      REGISTER_IP_DAILY: '100000',
      PROXY_IP_DAILY:    '100000',
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
  return { status: r.status, body: r.status < 400 ? await r.json() : null }
}

async function me(token) {
  const r = await fetch(`${proxyUrl}/me`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  return { status: r.status, body: r.status < 400 ? await r.json() : await r.json().catch(() => ({})) }
}

async function chat(token, content = 'hi') {
  const r = await fetch(`${proxyUrl}/proxy/chat/completions`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body:    JSON.stringify({ model: 'gpt-4o-mini', messages: [{role:'user',content}] }),
  })
  return { status: r.status, usedHeader: r.headers.get('x-usage-used'), body: await r.json().catch(() => ({})) }
}

// ── The tests ────────────────────────────────────────────────────────────

test('rejects /me without a token', async () => {
  const r = await me(undefined)
  assert.equal(r.status, 401)
})

test('rejects /device/register with too-short deviceId', async () => {
  const r = await register('short')
  assert.equal(r.status, 400)
})

test('/device/register is idempotent', async () => {
  const id = 'idempotent-device-12345'
  const a = await register(id)
  const b = await register(id)
  assert.equal(a.status, 200)
  assert.equal(b.status, 200)
  assert.equal(a.body.device.id, b.body.device.id, 'same DB primary key')
  assert.equal(a.body.device.plan, 'free')
  assert.equal(a.body.usage.limit, 2, 'FREE_DAILY=2 from test env')
})

test('/me returns plan and usage for the bound device', async () => {
  const reg = await register('me-test-device-12345')
  const r   = await me(reg.body.token)
  assert.equal(r.status, 200)
  assert.equal(r.body.device.plan, 'free')
  assert.equal(r.body.usage.limit, 2)
  assert.equal(r.body.usage.used, 0)
})

test('invalid JWT is rejected', async () => {
  const r = await me('totally.bogus.jwt')
  assert.equal(r.status, 401)
})

test('chat counts against the daily cap and the 4th hits 429', async () => {
  const reg = await register('cap-test-device-67890')
  const t   = reg.body.token

  const r1 = await chat(t); assert.equal(r1.status, 200, 'call 1 OK')
  const r2 = await chat(t); assert.equal(r2.status, 200, 'call 2 OK (limit reached)')
  const r3 = await chat(t); assert.equal(r3.status, 429, 'call 3 blocked')

  assert.equal(r3.body.plan, 'free')
  assert.equal(r3.body.limit, 2)
  assert.equal(r3.body.remaining, 0)
  assert.match(r3.body.upgradeHint, /Upgrade to Pro/)

  const meCheck = await me(t)
  assert.equal(meCheck.body.usage.used, 2, '429 did not increment the counter')
})

test('different deviceIds get independent quotas', async () => {
  const a = await register('quota-isolation-A-12345')
  const b = await register('quota-isolation-B-67890')

  // burn device A's quota
  await chat(a.body.token)
  await chat(a.body.token)
  const aBlocked = await chat(a.body.token)
  assert.equal(aBlocked.status, 429, 'A capped')

  // B still has the full quota
  const bMe = await me(b.body.token)
  assert.equal(bMe.body.usage.used, 0)
  assert.equal(bMe.body.usage.remaining, 2)

  const bCall = await chat(b.body.token)
  assert.equal(bCall.status, 200, 'B unaffected by A')
})

test('a failed upstream call is refunded — does not consume the daily cap', async () => {
  const reg = await register('refund-on-failure-device-13579')
  const t   = reg.body.token

  // Upstream 500s → proxy passes the error through but must NOT charge a slot.
  const failed = await chat(t, 'fail-upstream')
  assert.equal(failed.status, 500, 'upstream error is passed through verbatim')
  assert.equal(failed.usedHeader, '0', 'X-Usage-Used header reflects the refund')

  const afterFail = await me(t)
  assert.equal(afterFail.body.usage.used, 0, 'failed call refunded — counter still 0')

  // A subsequent good call works and is the only one that counts.
  const ok = await chat(t, 'hi')
  assert.equal(ok.status, 200, 'good call after a failure still works')
  const afterOk = await me(t)
  assert.equal(afterOk.body.usage.used, 1, 'only the successful call counted')
})

test('health endpoint exposes per-plan caps', async () => {
  const r    = await fetch(`${proxyUrl}/health`)
  const body = await r.json()
  assert.equal(r.status, 200)
  assert.equal(body.ok, true)
  assert.equal(body.plans.free, 2)
  assert.equal(body.plans.pro, 4)
})

// ── Cost guardrails (model pinning + token clamp) ──────────────────────────

test('rejects a chat request for a non-allowlisted (expensive) model', async () => {
  const reg = await register('device-bad-model-xyz')
  const t   = reg.body.token

  const r = await fetch(`${proxyUrl}/proxy/chat/completions`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${t}` },
    body:    JSON.stringify({ model: 'gpt-4o', messages: [{ role: 'user', content: 'hi' }] }),
  })
  assert.equal(r.status, 400, 'off-allowlist model is rejected before reaching OpenAI')

  // And the rejected call must not have consumed a daily slot.
  const after = await me(t)
  assert.equal(after.body.usage.used, 0, 'rejected model request did not burn quota')
})

test('rejects a chat request with no model field', async () => {
  const reg = await register('device-no-model-xyz')
  const t   = reg.body.token
  const r = await fetch(`${proxyUrl}/proxy/chat/completions`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${t}` },
    body:    JSON.stringify({ messages: [{ role: 'user', content: 'hi' }] }),
  })
  assert.equal(r.status, 400)
})

test('rejects an embeddings request for a non-allowlisted model', async () => {
  const reg = await register('device-bad-embed-xyz')
  const t   = reg.body.token
  const r = await fetch(`${proxyUrl}/proxy/embeddings`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${t}` },
    body:    JSON.stringify({ model: 'text-embedding-ada-002', input: 'hi' }),
  })
  assert.equal(r.status, 400)
})

// ── Per-IP registration throttle (Layer 2) ─────────────────────────────────
// Spins up an isolated proxy with a tiny REGISTER_IP_DAILY so we can prove the
// (N+1)th registration from the same IP is refused with 429.

test('throttles bulk device registration from one IP', async () => {
  const dir  = mkdtempSync(join(tmpdir(), 'shelfbot-proxy-rl-'))
  const port = 19000 + Math.floor(Math.random() * 900)
  const url  = `http://127.0.0.1:${port}`
  const proc = spawn(process.execPath, ['src/server.js'], {
    env: {
      ...process.env,
      OPENAI_API_KEY: 'sk-stub',
      JWT_SECRET:     'a'.repeat(32),
      PORT:           String(port),
      DB_PATH:        join(dir, 'rl.db'),
      REGISTER_IP_DAILY: '3',   // tiny cap so the 4th call trips it
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })

  try {
    await new Promise((resolve, reject) => {
      let buf = ''
      const timer = setTimeout(() => reject(new Error('proxy did not start: ' + buf)), 8000)
      proc.stdout.on('data', d => { buf += d; if (buf.includes('listening')) { clearTimeout(timer); resolve() } })
      proc.stderr.on('data', d => { buf += '[err] ' + d })
    })

    const reg = (i) => fetch(`${url}/device/register`, {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify({ deviceId: `rl-device-${i}-aaaaaaaa` }),
    }).then(r => r.status)

    assert.equal(await reg(1), 200, '1st registration allowed')
    assert.equal(await reg(2), 200, '2nd allowed')
    assert.equal(await reg(3), 200, '3rd allowed (at the cap)')
    assert.equal(await reg(4), 429, '4th from the same IP is throttled')
  } finally {
    proc.kill('SIGTERM')
    await new Promise(r => proc.once('exit', r))
    rmSync(dir, { recursive: true, force: true })
  }
})
