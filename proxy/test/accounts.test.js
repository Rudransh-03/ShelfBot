// Account / Google-sign-in / trial tests for the proxy.
//
// Google verification is exercised through the GOOGLE_AUTH_STUB seam (see
// google.js): the "authorization code" is base64url(JSON {sub,email,
// emailVerified}), so the whole /auth/google → account → trial → chat path runs
// with no network and no google-auth-library. Each test group spins up its own
// proxy so trial-window settings can differ.

import test   from 'node:test'
import assert from 'node:assert/strict'
import http   from 'node:http'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir }              from 'node:os'
import { join }                from 'node:path'
import { spawn }               from 'node:child_process'

// ── Shared OpenAI stub (always 200s with a canned completion) ───────────────
let openaiStub, openaiUrl
test.before(async () => {
  await new Promise((resolve) => {
    openaiStub = http.createServer((req, res) => {
      let body = ''
      req.on('data', c => { body += c })
      req.on('end', () => {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ id: 'stub', object: 'chat.completion',
          choices: [{ message: { role: 'assistant', content: 'stub' }, finish_reason: 'stop', index: 0 }] }))
      })
    })
    openaiStub.listen(0, '127.0.0.1', () => {
      openaiUrl = `http://127.0.0.1:${openaiStub.address().port}`
      resolve()
    })
  })
})
test.after(() => { if (openaiStub) openaiStub.close() })

// ── Helpers ─────────────────────────────────────────────────────────────────

const dirs  = []
const procs = []
async function startProxy(extraEnv = {}) {
  const dir  = mkdtempSync(join(tmpdir(), 'shelfbot-acct-'))
  dirs.push(dir)
  const port = 20000 + Math.floor(Math.random() * 2000)
  const url  = `http://127.0.0.1:${port}`
  const proc = spawn(process.execPath, ['src/server.js'], {
    env: {
      ...process.env,
      OPENAI_API_KEY:  'sk-stub',
      OPENAI_BASE_URL: openaiUrl,
      JWT_SECRET:      'a'.repeat(32),
      GOOGLE_AUTH_STUB: '1',
      PORT:            String(port),
      DB_PATH:         join(dir, 'acct.db'),
      REGISTER_IP_DAILY: '100000',
      PROXY_IP_DAILY:    '100000',
      ...extraEnv,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  await new Promise((resolve, reject) => {
    let buf = ''
    const timer = setTimeout(() => reject(new Error('proxy did not start: ' + buf)), 8000)
    proc.stdout.on('data', d => { buf += d; if (buf.includes('listening')) { clearTimeout(timer); resolve() } })
    proc.stderr.on('data', d => { buf += '[err] ' + d })
    proc.on('exit', code => { if (code) { clearTimeout(timer); reject(new Error('exited ' + code + ': ' + buf)) } })
  })
  procs.push(proc)
  return { url, proc }
}
// Kill every spawned proxy (don't leave them holding ports), then clean dirs.
test.after(async () => {
  for (const p of procs) { try { p.kill('SIGTERM') } catch {} }
  await new Promise(r => setTimeout(r, 200))
  for (const d of dirs) rmSync(d, { recursive: true, force: true })
})

const code = (sub, email, emailVerified = true) =>
  Buffer.from(JSON.stringify({ sub, email, emailVerified })).toString('base64url')

const signIn = (url, c) => fetch(`${url}/auth/google`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ code: c, codeVerifier: 'v'.repeat(43), redirectUri: 'http://127.0.0.1:1' }),
})
const chat = (url, token) => fetch(`${url}/proxy/chat/completions`, {
  method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
  body: JSON.stringify({ model: 'gpt-4o-mini', messages: [{ role: 'user', content: 'hi' }] }),
})

// ── Tests ─────────────────────────────────────────────────────────────────

test('Google sign-in creates a trial account with the 6-chat cap', async () => {
  const { url } = await startProxy({ TRIAL_DAILY: '6' })
  const r = await signIn(url, code('google-sub-1', 'Alice@Example.com'))
  assert.equal(r.status, 200)
  const b = await r.json()
  assert.ok(b.token, 'returns a JWT')
  assert.equal(b.account.plan, 'trial')
  assert.equal(b.account.email, 'alice@example.com', 'email normalized to lowercase')
  assert.equal(b.usage.limit, 6, 'trial cap = TRIAL_DAILY (6)')
  assert.equal(b.trial.active, true)
})

test('sign-in is idempotent on the Google subject (no fresh trial on reinstall)', async () => {
  const { url } = await startProxy()
  const a = await (await signIn(url, code('google-sub-2', 'bob@example.com'))).json()
  // burn one chat, then "reinstall" = sign in again with the same google sub
  await chat(url, a.token)
  const b = await (await signIn(url, code('google-sub-2', 'bob@example.com'))).json()
  assert.equal(a.account.id, b.account.id, 'same account row → trial cannot be reset by reinstalling')
  assert.equal(b.usage.used, 1, 'prior usage persists across re-sign-in')
})

test('unverified Google email is rejected', async () => {
  const { url } = await startProxy()
  const r = await signIn(url, code('google-sub-3', 'mallory@example.com', false))
  assert.equal(r.status, 403)
})

test('/me reflects the account, plan and trial', async () => {
  const { url } = await startProxy({ TRIAL_DAILY: '6' })
  const { token } = await (await signIn(url, code('google-sub-4', 'carol@example.com'))).json()
  const me = await (await fetch(`${url}/me`, { headers: { Authorization: `Bearer ${token}` } })).json()
  assert.equal(me.account.plan, 'trial')
  assert.equal(me.usage.limit, 6)
  assert.equal(me.usage.used, 0)
  assert.equal(me.trial.active, true)
})

test('trial chat counts against the cap and is blocked past it', async () => {
  const { url } = await startProxy({ TRIAL_DAILY: '2' })
  const { token } = await (await signIn(url, code('google-sub-5', 'dave@example.com'))).json()
  assert.equal((await chat(url, token)).status, 200, 'chat 1 OK')
  assert.equal((await chat(url, token)).status, 200, 'chat 2 OK (cap reached)')
  const r3 = await chat(url, token)
  assert.equal(r3.status, 429, 'chat 3 blocked')
  const b3 = await r3.json()
  assert.equal(b3.plan, 'trial')
  assert.equal(b3.remaining, 0)
})

test('reorg is blocked for trial accounts (chat-only)', async () => {
  const { url } = await startProxy()
  const { token } = await (await signIn(url, code('google-sub-6', 'erin@example.com'))).json()
  const r = await fetch(`${url}/reorg/start`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({}),
  })
  assert.equal(r.status, 403)
  assert.equal((await r.json()).reason, 'reorg_not_in_trial')
})

test('an expired trial blocks chat with 402', async () => {
  const { url } = await startProxy({ TRIAL_WINDOW_HOURS: '0' }) // any trial is already expired
  const { token } = await (await signIn(url, code('google-sub-7', 'frank@example.com'))).json()
  const r = await chat(url, token)
  assert.equal(r.status, 402, 'expired trial → payment required')
  assert.equal((await r.json()).reason, 'trial_ended')
})

test('legacy device registration still works alongside accounts', async () => {
  const { url } = await startProxy()
  const r = await fetch(`${url}/device/register`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId: 'legacy-device-abcdefgh' }),
  })
  assert.equal(r.status, 200)
  assert.equal((await r.json()).device.plan, 'free')
})
