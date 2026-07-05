// Backend process supervisor.
//
// Manages the Java backend as a resilient service so a user never has to
// restart the whole app because the JVM exited. Responsibilities:
//   • Supervised startup: launch → wait for health → bounded retries → clear
//     failure classification.
//   • Runtime monitoring: watch for unexpected process exit AND poll the health
//     endpoint; an unhealthy-but-alive backend is treated as a crash.
//   • Automatic recovery: restart on unexpected exit, with a max-attempts /
//     rolling-window / cooldown policy that prevents infinite restart loops.
//   • Graceful shutdown: request clean exit, wait, force-kill only if needed.
//
// This module is deliberately free of Electron imports so it is unit-testable:
// the caller injects `launch` (spawn + wait-for-ready), `checkHealth`, and a
// `logger`. index.js supplies the real implementations.

import { EventEmitter } from 'node:events'

/** Classified reasons for a startup failure or an exit. Surfaced to the UI so
 *  the user sees a specific message, never a generic "something went wrong". */
export const Reason = {
  JVM_MISSING:       'jvm-missing',
  PORT_CONFLICT:     'port-conflict',
  STARTUP_TIMEOUT:   'startup-timeout',
  STARTUP_FAILURE:   'startup-failure',
  CRASH:             'backend-crash',
  HEALTH_FAILURE:    'health-failure',
  TOO_MANY_RESTARTS: 'too-many-restarts',
  CLEAN_EXIT:        'clean-exit',
}

/** Human-readable, actionable text for each reason. */
export function describeReason(reason) {
  switch (reason) {
    case Reason.JVM_MISSING:
      return 'The Java runtime could not be found or started. Try reinstalling Rudo.'
    case Reason.PORT_CONFLICT:
      return 'Rudo could not open a local port for its backend. Another program may be using it.'
    case Reason.STARTUP_TIMEOUT:
      return 'The Rudo backend did not become ready in time.'
    case Reason.STARTUP_FAILURE:
      return 'The Rudo backend failed to start.'
    case Reason.CRASH:
      return 'The Rudo backend stopped unexpectedly.'
    case Reason.HEALTH_FAILURE:
      return 'The Rudo backend stopped responding.'
    case Reason.TOO_MANY_RESTARTS:
      return 'The Rudo backend keeps crashing. Please restart the app; if this persists, reinstall Rudo.'
    default:
      return 'The Rudo backend is unavailable.'
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/**
 * @fires ready       {port, durationMs}      — backend up and healthy (first start)
 * @fires restarting  {reason, attempt, message}
 * @fires restarted   {port, restartCount}    — backend recovered after a crash
 * @fires failed      {reason, message}       — permanent give-up (safe failure state)
 * @fires stopped     {}                      — graceful shutdown complete
 */
export class BackendSupervisor extends EventEmitter {
  constructor({
    launch,                       // async () => { child, port }; rejects with err.code on spawn failure
    checkHealth,                  // async (port) => boolean
    logger = console,
    startupRetries    = 2,        // extra attempts beyond the first, per bring-up
    startupTimeoutMs  = 35_000,   // budget for a single launch to reach "ready"
    healthWaitMs      = 12_000,   // after launch resolves, confirm health within this
    healthIntervalMs  = 5_000,    // runtime health poll cadence
    healthFailures    = 2,        // consecutive failed polls before we treat as crashed
    maxRestarts       = 5,        // restarts allowed within the rolling window
    restartWindowMs   = 60_000,   // rolling window for the restart budget
    cooldownMs        = 2_000,    // pause before each restart attempt
    shutdownTimeoutMs = 5_000,    // graceful-stop grace period before SIGKILL
  } = {}) {
    super()
    this.launch = launch
    this.checkHealth = checkHealth
    this.logger = logger
    this.cfg = {
      startupRetries, startupTimeoutMs, healthWaitMs, healthIntervalMs,
      healthFailures, maxRestarts, restartWindowMs, cooldownMs, shutdownTimeoutMs,
    }

    this.state = 'idle'
    this.child = null
    this.port = null
    this._stopping = false
    this._restartTimestamps = []
    this._healthTimer = null
    this._healthChecking = false
    this._consecutiveHealthFailures = 0
    this._restartCount = 0
  }

  getState() { return this.state }
  getPort()  { return this.port }
  getRestartCount() { return this._restartCount }

  /** Supervised startup. Resolves { port } when healthy; throws on permanent failure. */
  async start() {
    if (this.state === 'running') return { port: this.port }
    this._stopping = false
    this._transition('starting')
    const t0 = Date.now()

    const res = await this._bringUp()
    if (!res.ok) {
      this._fail(res.reason, res.message)
      const err = new Error(res.message || res.reason)
      err.reason = res.reason
      throw err
    }
    const durationMs = Date.now() - t0
    this._transition('running')
    this.logger.info(`[supervisor] backend ready on port ${res.port} in ${durationMs}ms`)
    this.emit('ready', { port: res.port, durationMs })
    return { port: res.port }
  }

  /** Graceful shutdown: request clean exit, wait, force-kill only if necessary. */
  async stop() {
    this._stopping = true
    this._transition('stopping')
    this._stopHealthMonitor()
    const child = this.child
    this.child = null
    if (!child || child.killed || child.exitCode != null) {
      this._transition('stopped')
      this.emit('stopped', {})
      return
    }
    this.logger.info('[supervisor] requesting graceful backend shutdown (SIGTERM)')
    const exited = new Promise((resolve) => child.once('exit', () => resolve(true)))
    try { child.kill('SIGTERM') } catch { /* already gone */ }

    const timedOut = await Promise.race([exited, sleep(this.cfg.shutdownTimeoutMs).then(() => false)])
    if (!timedOut) {
      this.logger.warn('[supervisor] graceful shutdown timed out — forcing SIGKILL')
      try { child.kill('SIGKILL') } catch { /* already gone */ }
      await Promise.race([exited, sleep(2_000)])
    }
    this._transition('stopped')
    this.emit('stopped', {})
  }

  // ── Bring-up (used by both start and restart) ──────────────────────────────

  async _bringUp() {
    const attempts = this.cfg.startupRetries + 1
    let last = { ok: false, reason: Reason.STARTUP_FAILURE, message: 'no attempt made' }
    for (let i = 1; i <= attempts; i++) {
      if (this._stopping) return { ok: false, reason: Reason.CLEAN_EXIT, message: 'stopping' }
      last = await this._launchOnce(i, attempts)
      if (last.ok) return last
      this.logger.warn(`[supervisor] start attempt ${i}/${attempts} failed: ${last.reason} — ${last.message}`)
      if (i < attempts) await sleep(this.cfg.cooldownMs)
    }
    return last
  }

  async _launchOnce(attempt, total) {
    let result
    try {
      result = await this._withTimeout(this.launch(), this.cfg.startupTimeoutMs)
    } catch (e) {
      return { ok: false, reason: this._classifySpawnError(e), message: e.message || String(e) }
    }
    const { child, port } = result || {}
    if (!child || !port) {
      return { ok: false, reason: Reason.STARTUP_FAILURE, message: 'launch returned no child/port' }
    }
    this.child = child
    this.port = port

    const healthy = await this._waitHealthy(port)
    if (!healthy) {
      await this._killChildNow(child)
      return { ok: false, reason: Reason.HEALTH_FAILURE, message: 'health check did not pass after start' }
    }

    this._attachExitHandler(child)
    this._startHealthMonitor()
    return { ok: true, port }
  }

  async _waitHealthy(port) {
    const deadline = Date.now() + this.cfg.healthWaitMs
    while (Date.now() < deadline) {
      if (this._stopping) return false
      try { if (await this.checkHealth(port)) return true } catch { /* keep polling */ }
      await sleep(300)
    }
    // One final attempt right at the deadline.
    try { return await this.checkHealth(port) } catch { return false }
  }

  // ── Runtime monitoring ──────────────────────────────────────────────────────

  _attachExitHandler(child) {
    child.once('exit', (code, signal) => {
      if (this.child !== child) return              // superseded (already restarted/stopped)
      this._stopHealthMonitor()
      this.child = null
      if (this._stopping || this.state === 'stopping' || this.state === 'stopped') return
      const reason = signal ? Reason.CRASH : (code === 0 ? Reason.CLEAN_EXIT : Reason.CRASH)
      this.logger.warn(`[supervisor] backend exited (code=${code}, signal=${signal}) → ${reason}`)
      // Even a code-0 exit is unexpected while we think it's running — recover.
      this._scheduleRestart(reason, `exit code=${code} signal=${signal}`)
    })
  }

  _startHealthMonitor() {
    this._stopHealthMonitor()
    this._consecutiveHealthFailures = 0
    this._healthTimer = setInterval(() => this._healthTick(), this.cfg.healthIntervalMs)
    if (this._healthTimer.unref) this._healthTimer.unref()
  }

  _stopHealthMonitor() {
    if (this._healthTimer) { clearInterval(this._healthTimer); this._healthTimer = null }
  }

  async _healthTick() {
    if (this.state !== 'running' || this._healthChecking || !this.port) return
    this._healthChecking = true
    try {
      const ok = await this.checkHealth(this.port)
      if (ok) {
        this._consecutiveHealthFailures = 0
      } else if (++this._consecutiveHealthFailures >= this.cfg.healthFailures) {
        this.logger.warn(`[supervisor] backend unhealthy for ${this._consecutiveHealthFailures} checks — recycling`)
        this._stopHealthMonitor()
        const child = this.child
        // Killing triggers the exit handler, which drives the restart path.
        if (child) { try { child.kill('SIGTERM') } catch { /* gone */ } }
        else { this._scheduleRestart(Reason.HEALTH_FAILURE, 'unhealthy with no live child') }
      }
    } catch {
      // fetch threw — count as a failed check
      if (++this._consecutiveHealthFailures >= this.cfg.healthFailures) {
        this._stopHealthMonitor()
        const child = this.child
        if (child) { try { child.kill('SIGTERM') } catch { /* gone */ } }
        else { this._scheduleRestart(Reason.HEALTH_FAILURE, 'health endpoint unreachable') }
      }
    } finally {
      this._healthChecking = false
    }
  }

  // ── Automatic recovery / restart policy ─────────────────────────────────────

  async _scheduleRestart(reason, message) {
    if (this._stopping) return
    const now = Date.now()
    this._restartTimestamps = this._restartTimestamps.filter((t) => now - t < this.cfg.restartWindowMs)
    if (this._restartTimestamps.length >= this.cfg.maxRestarts) {
      this._fail(Reason.TOO_MANY_RESTARTS,
        `exceeded ${this.cfg.maxRestarts} restarts within ${this.cfg.restartWindowMs}ms (last: ${reason})`)
      return
    }
    this._restartTimestamps.push(now)
    this._restartCount++
    this._transition('restarting')
    this.logger.warn(`[supervisor] restart #${this._restartCount} scheduled (reason=${reason}, ${message})`)
    this.emit('restarting', { reason, attempt: this._restartCount, message })

    await sleep(this.cfg.cooldownMs)
    if (this._stopping) return

    const res = await this._bringUp()
    if (res.ok) {
      this._transition('running')
      this.logger.info(`[supervisor] backend restarted on port ${res.port} (restart #${this._restartCount})`)
      this.emit('restarted', { port: res.port, restartCount: this._restartCount })
    } else {
      this._fail(res.reason, res.message)
    }
  }

  _fail(reason, message) {
    this._stopHealthMonitor()
    this._transition('failed')
    this.logger.error(`[supervisor] backend permanently unavailable: ${reason} — ${message}`)
    this.emit('failed', { reason, message: message || describeReason(reason) })
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  _transition(next) {
    if (this.state === next) return
    this.logger.info(`[supervisor] state ${this.state} → ${next}`)
    this.state = next
  }

  async _killChildNow(child) {
    if (!child) return
    const exited = new Promise((resolve) => child.once('exit', () => resolve()))
    try { child.kill('SIGTERM') } catch { /* gone */ }
    const done = await Promise.race([exited.then(() => true), sleep(2_000).then(() => false)])
    if (!done) { try { child.kill('SIGKILL') } catch { /* gone */ } }
    if (this.child === child) this.child = null
  }

  _classifySpawnError(e) {
    const code = e && e.code
    const msg = (e && e.message ? e.message : String(e)).toLowerCase()
    if (code === 'ENOENT' || msg.includes('enoent')) return Reason.JVM_MISSING
    if (code === 'EADDRINUSE' || msg.includes('eaddrinuse') || msg.includes('address in use')) return Reason.PORT_CONFLICT
    if (msg.includes('timed out') || msg.includes('timeout')) return Reason.STARTUP_TIMEOUT
    return Reason.STARTUP_FAILURE
  }

  _withTimeout(promise, ms) {
    let timer
    const timeout = new Promise((_, reject) => {
      timer = setTimeout(() => {
        const err = new Error(`startup timed out after ${ms}ms`)
        err.code = 'ETIMEDOUT'
        reject(err)
      }, ms)
    })
    return Promise.race([promise, timeout]).finally(() => clearTimeout(timer))
  }
}
