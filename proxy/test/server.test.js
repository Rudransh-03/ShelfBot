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
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ id: 'stub', object: 'chat.completion', choices: [
        { message: { role: 'assistant', content: 'stub' }, finish_reason: 'stop', index: 0 }
      ]}))
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

async function chat(token) {
  const r = await fetch(`${proxyUrl}/proxy/chat/completions`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body:    JSON.stringify({ model: 'gpt-4o-mini', messages: [{role:'user',content:'hi'}] }),
  })
  return { status: r.status, body: await r.json().catch(() => ({})) }
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

test('health endpoint exposes per-plan caps', async () => {
  const r    = await fetch(`${proxyUrl}/health`)
  const body = await r.json()
  assert.equal(r.status, 200)
  assert.equal(body.ok, true)
  assert.equal(body.plans.free, 2)
  assert.equal(body.plans.pro, 4)
})
