import { test } from 'node:test'
import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { BackendSupervisor, Reason } from '../src/main/supervisor.js'

// ── Test doubles ─────────────────────────────────────────────────────────────

let pidCounter = 1000

/** A fake child process. `autoExitOn` names the signal that makes it exit. */
function makeFakeChild({ autoExitOn = null } = {}) {
  const child = new EventEmitter()
  child.pid = ++pidCounter
  child.killed = false
  child.exitCode = null
  child.signals = []
  child.kill = (sig) => {
    child.killed = true
    child.signals.push(sig)
    if (autoExitOn && sig === autoExitOn) {
      queueMicrotask(() => { child.exitCode = 0; child.emit('exit', 0, sig) })
    }
    return true
  }
  child.simulateExit = (code, signal) => { child.exitCode = code; child.emit('exit', code, signal) }
  return child
}

const silentLogger = { info() {}, warn() {}, error() {} }

/** Fast timings so the whole suite runs in well under a second. */
const fast = {
  logger: silentLogger,
  startupRetries: 0,
  startupTimeoutMs: 300,
  healthWaitMs: 200,
  healthIntervalMs: 40,
  healthFailures: 2,
  maxRestarts: 2,
  restartWindowMs: 2000,
  cooldownMs: 5,
  shutdownTimeoutMs: 40,
}

const once = (emitter, event) => new Promise((resolve) => emitter.once(event, resolve))

// ── Startup ──────────────────────────────────────────────────────────────────

test('supervised startup: launches, confirms health, emits ready', async () => {
  const child = makeFakeChild({ autoExitOn: 'SIGKILL' })
  const sup = new BackendSupervisor({
    ...fast,
    launch: async () => ({ child, port: 9876 }),
    checkHealth: async () => true,
  })
  const readyP = once(sup, 'ready')
  const { port } = await sup.start()
  const evt = await readyP
  assert.equal(port, 9876)
  assert.equal(evt.port, 9876)
  assert.equal(sup.getState(), 'running')
  await sup.stop()
})

test('startup failure (JVM missing) rejects with classified reason and emits failed', async () => {
  const sup = new BackendSupervisor({
    ...fast,
    launch: async () => { const e = new Error('spawn java ENOENT'); e.code = 'ENOENT'; throw e },
    checkHealth: async () => true,
  })
  const failedP = once(sup, 'failed')
  await assert.rejects(() => sup.start(), (e) => e.reason === Reason.JVM_MISSING)
  const evt = await failedP
  assert.equal(evt.reason, Reason.JVM_MISSING)
  assert.equal(sup.getState(), 'failed')
})

test('startup health failure: process starts but never becomes healthy', async () => {
  const child = makeFakeChild({ autoExitOn: 'SIGTERM' })
  const sup = new BackendSupervisor({
    ...fast,
    launch: async () => ({ child, port: 5000 }),
    checkHealth: async () => false,   // never healthy
  })
  await assert.rejects(() => sup.start(), (e) => e.reason === Reason.HEALTH_FAILURE)
  assert.equal(sup.getState(), 'failed')
})

test('startup timeout: launch never resolves', async () => {
  const sup = new BackendSupervisor({
    ...fast,
    startupTimeoutMs: 60,
    launch: () => new Promise(() => {}),   // hangs forever
    checkHealth: async () => true,
  })
  await assert.rejects(() => sup.start(), (e) => e.reason === Reason.STARTUP_TIMEOUT)
})

// ── Automatic recovery ───────────────────────────────────────────────────────

test('unexpected crash triggers automatic restart', async () => {
  const children = [makeFakeChild(), makeFakeChild({ autoExitOn: 'SIGKILL' })]
  let n = 0
  const sup = new BackendSupervisor({
    ...fast,
    launch: async () => ({ child: children[n++], port: 7000 + n }),
    checkHealth: async () => true,
  })
  await sup.start()
  assert.equal(sup.getPort(), 7001)

  const restartingP = once(sup, 'restarting')
  const restartedP = once(sup, 'restarted')
  children[0].simulateExit(1, null)      // crash

  const restarting = await restartingP
  assert.equal(restarting.reason, Reason.CRASH)
  const restarted = await restartedP
  assert.equal(restarted.port, 7002)
  assert.equal(sup.getState(), 'running')
  assert.equal(sup.getRestartCount(), 1)
  await sup.stop()
})

test('repeated crashes stop restarting after maxRestarts (safe failure state)', async () => {
  const sup = new BackendSupervisor({
    ...fast,
    maxRestarts: 2,
    launch: async () => ({ child: makeFakeChild(), port: 8000 }),
    checkHealth: async () => true,
  })
  // Every time the backend comes up, immediately crash it.
  const crash = () => { const c = sup.child; if (c) queueMicrotask(() => c.simulateExit(1, null)) }
  sup.on('ready', crash)
  sup.on('restarted', crash)

  const failedP = once(sup, 'failed')
  await sup.start().catch(() => {})     // start resolves 'ready' then we crash
  const evt = await failedP
  assert.equal(evt.reason, Reason.TOO_MANY_RESTARTS)
  assert.equal(sup.getState(), 'failed')
  assert.equal(sup.getRestartCount(), 2)  // exactly maxRestarts before giving up
})

// ── Runtime health monitoring ────────────────────────────────────────────────

test('health monitor recycles an alive-but-unhealthy backend', async () => {
  const children = [makeFakeChild({ autoExitOn: 'SIGTERM' }), makeFakeChild({ autoExitOn: 'SIGKILL' })]
  let n = 0
  let healthy = true
  const sup = new BackendSupervisor({
    ...fast,
    launch: async () => ({ child: children[n++], port: 6000 }),
    checkHealth: async () => healthy,
  })
  await sup.start()
  const restartedP = once(sup, 'restarted')
  // The replacement backend comes up healthy again.
  sup.once('restarting', () => { healthy = true })
  healthy = false   // start failing health checks → monitor should recycle

  const evt = await restartedP
  assert.equal(evt.port, 6000)
  // The first child was asked to terminate.
  assert.ok(children[0].signals.includes('SIGTERM'))
  await sup.stop()
})

// ── Shutdown ─────────────────────────────────────────────────────────────────

test('graceful shutdown: SIGTERM, waits for exit, no restart', async () => {
  const child = makeFakeChild({ autoExitOn: 'SIGTERM' })
  const sup = new BackendSupervisor({
    ...fast,
    launch: async () => ({ child, port: 4321 }),
    checkHealth: async () => true,
  })
  await sup.start()
  let restarted = false
  sup.on('restarting', () => { restarted = true })

  const stoppedP = once(sup, 'stopped')
  await sup.stop()
  await stoppedP
  assert.equal(child.signals[0], 'SIGTERM')
  assert.equal(restarted, false)
  assert.equal(sup.getState(), 'stopped')
})

test('forced shutdown: SIGKILL after graceful timeout', async () => {
  const child = makeFakeChild({ autoExitOn: 'SIGKILL' })  // ignores SIGTERM
  const sup = new BackendSupervisor({
    ...fast,
    launch: async () => ({ child, port: 4322 }),
    checkHealth: async () => true,
  })
  await sup.start()
  await sup.stop()
  assert.ok(child.signals.includes('SIGTERM'))
  assert.ok(child.signals.includes('SIGKILL'))
})
