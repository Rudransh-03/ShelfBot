import { app, BrowserWindow, dialog, ipcMain, safeStorage, shell } from 'electron'
import { join }   from 'path'
import { spawn, execFile }  from 'child_process'
import { existsSync, readFileSync, writeFileSync, mkdirSync, unlinkSync, chmodSync } from 'fs'
import { createServer } from 'net'
import { createServer as createHttpServer } from 'http'
import { machineIdSync } from 'node-machine-id'
import { randomUUID, randomBytes, createHash } from 'crypto'
import { promisify }     from 'util'
import { buildCalendar } from './ics.js'
import { BackendSupervisor, describeReason } from './supervisor.js'

const execFileP = promisify(execFile)

// ─────────────────────────────────────────────────────────────────────────────
// Auth: persist proxy JWT in OS-encrypted secure storage
// ─────────────────────────────────────────────────────────────────────────────
//
// safeStorage encrypts with a key managed by the OS keychain (macOS Keychain,
// Windows DPAPI, Linux Secret Service). So even if someone reads the file off
// disk, they get ciphertext — only this app, on this user account, can decrypt.

// ── Proxy URL: dev vs production ─────────────────────────────────────────────
// The auth/licensing proxy (and the OpenAI key) lives on a server you host. To
// GO LIVE, set PROD_PROXY_URL to your deployed proxy (HTTPS) — that's the only
// change needed. Packaged builds use it automatically; dev uses localhost; an
// explicit SHELFBOT_PROXY_URL env var overrides either (handy for staging).
// This single resolved value is also handed to the Java backend on spawn, so
// the desktop app and the backend always talk to the same proxy.
const PROD_PROXY_URL = 'https://CHANGE-ME-to-your-deployed-proxy.example.com' // TODO: set on deploy
const PROXY_URL = process.env.SHELFBOT_PROXY_URL
  || (app.isPackaged ? PROD_PROXY_URL : 'http://localhost:8787')

// Guard: a packaged build must not ship the placeholder proxy URL — nothing
// (chat, sign-in, deadlines) would work. Surface it loudly instead of failing
// silently at runtime. (No-op in dev, which uses localhost.)
if (app.isPackaged && PROXY_URL.includes('CHANGE-ME')) {
  console.error('[ShelfBot] FATAL: PROD_PROXY_URL is still the placeholder. '
    + 'Set it (or SHELFBOT_PROXY_URL) to your deployed proxy before building for release.')
}

// Google OAuth client id for the "Sign in with Google" flow. This is a PUBLIC
// value (safe to ship in the app) — the matching client SECRET lives only on
// the proxy, which is what actually exchanges the auth code. Create a
// "Desktop app" OAuth client in Google Cloud and put its id here (or pass
// SHELFBOT_GOOGLE_CLIENT_ID in dev). Loopback (127.0.0.1) redirects with any
// port are auto-allowed for Desktop clients, so no redirect URIs to pre-register.
const GOOGLE_CLIENT_ID = process.env.SHELFBOT_GOOGLE_CLIENT_ID
  || '828216281643-8cavncibl3t9jmjonv41ln8nol6uvh9p.apps.googleusercontent.com'
const TOKEN_FILE   = () => join(app.getPath('userData'), 'auth.token')
const DEVICE_FILE  = () => join(app.getPath('userData'), 'device.id')

/**
 * Returns this installation's stable device identifier. Priority order:
 *   1. A UUID we wrote to userData on a previous launch.
 *   2. The OS-reported machine ID (node-machine-id; hashed for privacy).
 *   3. A freshly generated UUID, persisted for next time.
 *
 * Why the cached UUID wins: node-machine-id can change if the OS is
 * reinstalled or the secure-boot key rotates — we don't want a free user
 * to silently get a brand new quota every OS reinstall. Persisting a UUID
 * to userData makes identity portable across OS quirks while still being
 * bound to this specific install.
 */
function getDeviceId() {
  const path = DEVICE_FILE()
  if (existsSync(path)) {
    try {
      const cached = readFileSync(path, 'utf8').trim()
      if (cached.length >= 8) return cached
    } catch {}
  }
  let id
  try {
    // machineIdSync(true) returns the original; default returns SHA-256 of it.
    // Hashed is what we want — no need to know the actual MAC/UUID.
    id = machineIdSync()
  } catch {
    id = null
  }
  if (!id || id.length < 8) id = randomUUID()
  try {
    mkdirSync(join(app.getPath('userData')), { recursive: true })
    writeFileSync(path, id, 'utf8')
  } catch (e) {
    console.warn('[ShelfBot] could not persist device id:', e.message)
  }
  return id
}

function persistToken(tokenJson) {
  try {
    mkdirSync(join(app.getPath('userData')), { recursive: true })
    if (!safeStorage.isEncryptionAvailable()) {
      console.warn('[ShelfBot] safeStorage unavailable; storing token in plaintext')
      writeFileSync(TOKEN_FILE(), tokenJson, 'utf8')
    } else {
      const encrypted = safeStorage.encryptString(tokenJson)
      writeFileSync(TOKEN_FILE(), encrypted)
    }
    console.log('[ShelfBot] auth token persisted to', TOKEN_FILE())
  } catch (e) {
    console.error('[ShelfBot] failed to persist auth token:', e.message)
  }
}

function loadToken() {
  const path = TOKEN_FILE()
  if (!existsSync(path)) return null
  try {
    const buf = readFileSync(path)
    if (safeStorage.isEncryptionAvailable()) {
      return JSON.parse(safeStorage.decryptString(buf))
    }
    return JSON.parse(buf.toString('utf8'))
  } catch (e) {
    console.warn('[ShelfBot] could not decrypt auth token:', e.message)
    return null
  }
}

function clearToken() {
  const path = TOKEN_FILE()
  try { if (existsSync(path)) unlinkSync(path) } catch {}
}

// ─────────────────────────────────────────────────────────────────────────────
// Auto-updater
// ─────────────────────────────────────────────────────────────────────────────
// We lazy-require so a packaged build without the dep (or `npm install` not
// yet run in development) doesn't crash the app. The updater is meaningful
// only for packaged builds — in dev `app.isPackaged` is false and we skip it
// entirely.

let autoUpdater = null
try {
  if (app.isPackaged) {
    autoUpdater = require('electron-updater').autoUpdater
    // Don't auto-restart silently. We surface a UI banner and let the user pick.
    autoUpdater.autoDownload         = true
    autoUpdater.autoInstallOnAppQuit = true

    // Route updater logs to electron-log so we can diagnose issues from
    // user reports without needing to ship console output. Lives at the
    // platform default location (~/Library/Logs/ShelfBot on macOS, etc.).
    try {
      const log = require('electron-log')
      log.transports.file.level = 'info'
      autoUpdater.logger = log
    } catch (e) {
      console.warn('[ShelfBot] electron-log not available:', e.message)
    }
  }
} catch (e) {
  console.warn('[ShelfBot] electron-updater not available; auto-updates disabled:', e.message)
  autoUpdater = null
}

// ─────────────────────────────────────────────────────────────────────────────
// Paths
// ─────────────────────────────────────────────────────────────────────────────

// app.getAppPath() → .../shelfbot/ui  (where package.json lives)
const UI_ROOT      = app.getAppPath()
const BACKEND_ROOT = join(UI_ROOT, '..', 'backend')
const JAR_DEV      = join(BACKEND_ROOT, 'target', 'local-file-brain-1.0.0.jar')
// When packaged, electron-builder copies the JAR to resources/
const JAR_PROD     = join(process.resourcesPath ?? '', 'shelfbot.jar')

const DEFAULT_PORT   = 9876
const START_TIMEOUT  = 35_000 // ms

// ─────────────────────────────────────────────────────────────────────────────
// Persistent user-data directory
// ─────────────────────────────────────────────────────────────────────────────
//
// ALL durable backend data (Lucene index, metadata + chat DBs, config, logs)
// lives here — the OS-standard per-user application-data directory, NEVER the
// working directory or the app bundle. This is what makes user data survive an
// auto-update (which replaces the bundle wholesale) and keeps the macOS code
// signature intact. The same value is handed to the Java backend via
// SHELFBOT_DATA_DIR so both sides agree on one location.
//
//   macOS   → ~/Library/Application Support/Rudo
//   Windows → %LOCALAPPDATA%\Rudo
//   Linux   → $XDG_DATA_HOME/Rudo  (else ~/.local/share/Rudo)
function resolveDataDir() {
  if (process.env.SHELFBOT_DATA_DIR && process.env.SHELFBOT_DATA_DIR.trim()) {
    return process.env.SHELFBOT_DATA_DIR.trim()
  }
  if (process.platform === 'darwin') {
    return join(app.getPath('appData'), 'Rudo') // appData = ~/Library/Application Support
  }
  if (process.platform === 'win32') {
    return join(process.env.LOCALAPPDATA || app.getPath('appData'), 'Rudo')
  }
  const xdg = process.env.XDG_DATA_HOME || join(app.getPath('home'), '.local', 'share')
  return join(xdg, 'Rudo')
}

const DATA_DIR = resolveDataDir()
const LOG_DIR  = join(DATA_DIR, 'logs')

// Per-launch secret shared only between this main process, the Java backend
// (passed via env), and our own renderer (passed over IPC). The backend rejects
// any /api/* request that doesn't echo it in X-Shelfbot-Token — so a malicious
// web page in the user's browser can reach the localhost port but can't read
// their private documents/chat, because it can't know this value.
const LOCAL_API_TOKEN = randomUUID()

let mainWindow  = null
let supervisor  = null

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

function getJarPath() {
  if (app.isPackaged && existsSync(JAR_PROD)) return JAR_PROD
  if (existsSync(JAR_DEV))                    return JAR_DEV
  return null
}

/**
 * Resolves the `java` executable to launch the backend with.
 *
 * Packaged builds ship a slim, self-contained Java runtime under
 * resources/runtime/ (built by scripts/build-runtime.mjs via jlink), so the
 * end user never needs Java installed. We use that bundled runtime whenever
 * it's present; otherwise we fall back to a system `java` on PATH (covers
 * `npm run dev`, and is a graceful last resort if the bundle is missing).
 *
 * In dev we'll also use a locally-built runtime if one exists, so the exact
 * packaged code path can be exercised without making a full installer.
 */
function getJavaBin() {
  const exe = process.platform === 'win32' ? 'java.exe' : 'java'
  const candidate = app.isPackaged
    ? join(process.resourcesPath, 'runtime', 'bin', exe)
    : join(BACKEND_ROOT, 'target', 'runtime', 'bin', exe)

  if (existsSync(candidate)) {
    // extraResources should preserve the exec bit, but ensure it on *nix so a
    // copy that dropped permissions can't break launch.
    if (process.platform !== 'win32') {
      try { chmodSync(candidate, 0o755) } catch { /* best effort */ }
    }
    return candidate
  }

  if (app.isPackaged) {
    console.warn('[ShelfBot] bundled Java runtime not found at', candidate,
      '— falling back to system java')
  }
  return 'java'
}

function findFreePort(from = DEFAULT_PORT) {
  return new Promise((resolve, reject) => {
    const srv = createServer()
    srv.listen(from, '127.0.0.1', () => {
      const p = srv.address().port
      srv.close(() => resolve(p))
    })
    srv.on('error', () => findFreePort(from + 1).then(resolve).catch(reject))
  })
}

/**
 * Kills any orphaned ShelfBot Java process before we spawn a new one.
 *
 * Lucene allows only one writer per index directory; if a previous Electron
 * launch was force-quit (Cmd-Q during indexing, crash, IDE stop button), the
 * Java child can survive and hold the write.lock — every subsequent
 * `npm run dev` then fails with LockObtainFailedException and the UI loads
 * with no backend. We sweep for stale instances on every start so dev runs
 * are reliable.
 *
 * macOS / Linux: use `pgrep -f` against the JAR name. Windows: best-effort
 * via wmic; failure is logged and ignored (rare in dev for this project).
 *
 * After killing, also clear a leftover write.lock — on hard kill the JVM
 * doesn't get to release it via shutdown hook.
 */
async function killOrphanedJava() {
  const isWin = process.platform === 'win32'
  let pids = []
  try {
    if (isWin) {
      // wmic returns lines like:  CommandLine=java ... ProcessId=12345
      const { stdout } = await execFileP('wmic', ['process', 'where', "name='java.exe'", 'get', 'CommandLine,ProcessId', '/format:list'])
      for (const block of stdout.split(/\r?\n\r?\n/)) {
        if (block.includes('local-file-brain') || block.includes('shelfbot.jar')) {
          const m = block.match(/ProcessId=(\d+)/)
          if (m) pids.push(parseInt(m[1], 10))
        }
      }
    } else {
      const { stdout } = await execFileP('pgrep', ['-f', 'local-file-brain|shelfbot\\.jar'])
      pids = stdout.split('\n').filter(Boolean).map(s => parseInt(s.trim(), 10))
    }
  } catch (e) {
    // pgrep exits 1 when nothing matches — that's the happy case.
    if (e.code !== 1) console.warn('[ShelfBot] orphan-scan failed:', e.message)
    return
  }

  // Don't kill our own live child (during a supervised restart the previous
  // child has already exited, so this is just a safety-guard).
  const ownPid = supervisor?.child?.pid
  pids = pids.filter(p => p !== ownPid)
  if (pids.length === 0) return

  console.log('[ShelfBot] killing orphan Java pid(s):', pids.join(', '))
  for (const pid of pids) {
    try { process.kill(pid, 'SIGTERM') } catch {}
  }

  // Give them a moment to release the lock, then SIGKILL any survivors.
  await new Promise(r => setTimeout(r, 1200))
  for (const pid of pids) {
    try { process.kill(pid, 0); process.kill(pid, 'SIGKILL') } catch {}
  }

  // Some hard kills leave Lucene's write.lock on disk. Remove it so the
  // fresh JVM can open the index. Lock contents don't matter — Lucene
  // recreates on open.
  const lockFile = join(DATA_DIR, 'shelfbot-vector-index', 'write.lock')
  try {
    if (existsSync(lockFile)) {
      unlinkSync(lockFile)
      console.log('[ShelfBot] removed stale Lucene write.lock')
    }
  } catch (e) {
    console.warn('[ShelfBot] could not remove stale lock:', e.message)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Java backend
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Spawns the Java backend ONCE and resolves { child, port } as soon as it
 * prints its readiness line. Rejects (with an error code the supervisor can
 * classify) if the JVM can't be found, the process errors, or it exits before
 * signalling ready. The BackendSupervisor owns retries, health monitoring, and
 * restart policy — this function is just "start one instance".
 */
function launchBackendOnce() {
  const jar = getJarPath()
  if (!jar) {
    const e = new Error('Backend JAR not found — run `mvn package` inside backend/')
    e.code = 'ENOENT'
    return Promise.reject(e)
  }

  return new Promise(async (resolve, reject) => {
    // Always sweep orphaned JVMs first — see killOrphanedJava() for why.
    await killOrphanedJava()
    const port    = await findFreePort()
    const javaBin = getJavaBin()
    try { mkdirSync(LOG_DIR, { recursive: true }) } catch { /* best effort */ }
    console.log(`[ShelfBot] Starting Java on port ${port}  (${javaBin} -jar ${jar})`)

    let child
    try {
      // -Djava.awt.headless=true keeps the JVM from initializing AWT (no Java
      //   dock icon on macOS; no windowing system on headless Linux).
      // -Drudo.log.dir points Logback's rolling file appender at the data dir.
      child = spawn(javaBin, [
        '-Djava.awt.headless=true',
        `-Drudo.log.dir=${LOG_DIR}`,
        '-Xmx512m',
        '-jar', jar, '--server', '--port', String(port),
      ], {
        cwd:   DATA_DIR,     // never the app bundle; keeps any stray relative path safe
        stdio: ['ignore', 'pipe', 'pipe'],
        // Spread process.env for PATH etc.; the secrets/paths after the spread
        // are authoritative. Passing via env (not argv) keeps the token out of
        // `ps`/pgrep. SHELFBOT_DATA_DIR pins the data root; SHELFBOT_LEGACY_DIR
        // tells the backend where to look for pre-existing data to migrate.
        env: {
          ...process.env,
          SHELFBOT_LOCAL_TOKEN: LOCAL_API_TOKEN,
          SHELFBOT_PROXY_URL:   PROXY_URL,
          SHELFBOT_DATA_DIR:    DATA_DIR,
          SHELFBOT_LEGACY_DIR:  BACKEND_ROOT,
        },
      })
    } catch (err) {
      reject(err)
      return
    }

    // Permanent passthrough so dev still sees backend logs on the console.
    child.stdout.on('data', d => process.stdout.write('[Java] ' + d.toString()))
    child.stderr.on('data', d => process.stderr.write('[Java/err] ' + d.toString()))

    let settled = false
    const onData = (data) => {
      const m = data.toString().match(/SHELFBOT_SERVER_READY:(\d+)/)
      if (m && !settled) { settled = true; cleanup(); resolve({ child, port: parseInt(m[1], 10) }) }
    }
    const onError = (err) => { if (!settled) { settled = true; cleanup(); reject(err) } }
    const onExit  = (code, signal) => {
      if (!settled) {
        settled = true; cleanup()
        const e = new Error(`backend exited before ready (code=${code}, signal=${signal})`)
        e.code = code
        reject(e)
      }
    }
    function cleanup() {
      child.stdout.off('data', onData)
      child.stderr.off('data', onData)
      child.off('error', onError)
      child.off('exit',  onExit)
    }
    // READY line can land on stdout or (via Logback) stderr.
    child.stdout.on('data', onData)
    child.stderr.on('data', onData)
    child.once('error', onError)
    child.once('exit',  onExit)
  })
}

/** Liveness probe used by the supervisor's runtime health monitor. */
async function checkBackendHealth(port) {
  try {
    const res = await fetch(`http://localhost:${port}/api/health`, {
      signal: AbortSignal.timeout(4_000),
    })
    return res.ok
  } catch {
    return false
  }
}

/** Sends the current API port + local token to the renderer so it (re)connects. */
function sendApiPort(port) {
  lastApiPort = port
  sendToRenderer('api-port', { port, token: LOCAL_API_TOKEN })
}

/** Broadcasts supervisor lifecycle to the renderer for user-facing notices. */
function sendBackendStatus(payload) {
  sendToRenderer('backend-status', payload)
}

/** Builds + wires the supervisor. Idempotent — reuses an existing instance. */
function createSupervisor() {
  if (supervisor) return supervisor
  supervisor = new BackendSupervisor({
    launch:           launchBackendOnce,
    checkHealth:      checkBackendHealth,
    logger:           console,
    startupTimeoutMs: START_TIMEOUT,
  })

  supervisor.on('ready', ({ port, durationMs }) => {
    console.log(`[ShelfBot] backend ready on ${port} (${durationMs}ms)`)
    sendApiPort(port)
    sendBackendStatus({ state: 'ready', port })
  })
  supervisor.on('restarting', ({ reason, attempt, message }) => {
    sendBackendStatus({ state: 'restarting', reason, attempt, message: describeReason(reason) })
    console.warn(`[ShelfBot] backend restarting (attempt ${attempt}): ${message}`)
  })
  supervisor.on('restarted', ({ port, restartCount }) => {
    console.log(`[ShelfBot] backend restarted on ${port} (restart #${restartCount})`)
    sendApiPort(port)
    // The fresh JVM has no auth token yet — re-push the saved JWT so chat,
    // deadlines, and extraction keep working without a manual re-sign-in.
    const saved = loadToken()
    if (saved?.token) pushTokenToBackend(saved.token)
    sendBackendStatus({ state: 'restarted', port, restartCount })
  })
  supervisor.on('failed', ({ reason, message }) => {
    console.error(`[ShelfBot] backend permanently unavailable: ${reason} — ${message}`)
    sendBackendStatus({ state: 'failed', reason, message: describeReason(reason) })
  })
  supervisor.on('stopped', () => sendBackendStatus({ state: 'stopped' }))

  return supervisor
}

// ─────────────────────────────────────────────────────────────────────────────
// Window
// ─────────────────────────────────────────────────────────────────────────────

function createWindow(port) {
  mainWindow = new BrowserWindow({
    width:  1180,
    height: 760,
    minWidth:  860,
    minHeight: 580,
    frame:           false,
    titleBarStyle:   'hidden',
    backgroundColor: '#f2ede6', // paper — no dark flash before React mounts
    show:            false,
    webPreferences: {
      preload:          join(__dirname, '../preload/index.js'),
      contextIsolation: true,
      nodeIntegration:  false,
      sandbox:          false,
    },
  })

  // electron-vite hot-reloads in dev via VITE_DEV_SERVER_URL
  if (process.env['ELECTRON_RENDERER_URL']) {
    mainWindow.loadURL(process.env['ELECTRON_RENDERER_URL'])
  } else {
    mainWindow.loadFile(join(__dirname, '../renderer/index.html'))
  }

  // Hardening: the renderer should never navigate itself away from the app
  // shell, and window.open should not spawn arbitrary Electron windows. A
  // compromised/injected renderer is thus contained — external links still
  // open, but in the user's real browser, and only for safe schemes.
  mainWindow.webContents.on('will-navigate', (e, url) => {
    const appUrl = process.env['ELECTRON_RENDERER_URL']
    if (appUrl && url.startsWith(appUrl)) return // allow Vite HMR in dev
    e.preventDefault()
    if (/^https?:/i.test(url)) shell.openExternal(url)
  })
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (/^(https?|mailto):/i.test(url)) shell.openExternal(url)
    return { action: 'deny' }
  })

  mainWindow.webContents.on('did-finish-load', () => {
    // Use the supervisor's current port (source of truth) — it may have changed
    // if the backend restarted before the window finished loading. Null when
    // the backend failed to start; the renderer then relies on backend-status.
    const p = lastApiPort ?? port
    if (p) mainWindow.webContents.send('api-port', { port: p, token: LOCAL_API_TOKEN })
    mainWindow.show()
  })

  mainWindow.on('closed', () => { mainWindow = null })
}

// ─────────────────────────────────────────────────────────────────────────────
// App lifecycle
// ─────────────────────────────────────────────────────────────────────────────

// Single-instance guard: a second launch must NOT spawn a second backend —
// killOrphanedJava() would then kill the FIRST instance's Java (and release its
// Lucene write lock), breaking the already-running app. Focus the existing
// window and quit the duplicate instead.
if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore()
      mainWindow.focus()
    }
  })

  app.whenReady().then(async () => {
    console.log('[ShelfBot] app name:', app.getName())
    console.log('[ShelfBot] userData:', app.getPath('userData'))
    console.log('[ShelfBot] data dir:', DATA_DIR)
    console.log('[ShelfBot] token file:', TOKEN_FILE())
    console.log('[ShelfBot] device file:', DEVICE_FILE())
    console.log('[ShelfBot] safeStorage available:', safeStorage.isEncryptionAvailable())
    console.log('[ShelfBot] proxy url:', PROXY_URL)

    createSupervisor()
    let port = null
    try {
      const r = await supervisor.start()
      port = r.port
    } catch (e) {
      // Permanent startup failure. Still open the window so the user sees a
      // specific, actionable error (via backend-status) instead of nothing.
      console.error('[ShelfBot] backend failed to start:', e.reason || e.message)
    }
    createWindow(port)
    // Defer the update check so the window paints first and the user isn't
    // staring at a hanging window while we hit GitHub.
    setTimeout(initAutoUpdater, 4_000)
  })

  app.on('window-all-closed', () => { app.quit() })

  // Graceful shutdown: give the supervisor a chance to stop the backend cleanly
  // (clean commit + close) before the app exits. Guarded so we don't loop.
  let shuttingDown = false
  app.on('before-quit', (e) => {
    if (shuttingDown || !supervisor) return
    e.preventDefault()
    shuttingDown = true
    Promise.resolve(supervisor.stop()).catch(() => {}).finally(() => app.quit())
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// Auto-updater event wiring
// ─────────────────────────────────────────────────────────────────────────────

function sendToRenderer(channel, payload) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send(channel, payload)
  }
}

function initAutoUpdater() {
  if (!autoUpdater) return

  autoUpdater.on('checking-for-update', () => {
    console.log('[updater] checking…')
  })
  autoUpdater.on('update-available', (info) => {
    console.log('[updater] update available:', info.version)
    sendToRenderer('update-status', { state: 'available', version: info.version })
  })
  autoUpdater.on('update-not-available', () => {
    sendToRenderer('update-status', { state: 'none' })
  })
  autoUpdater.on('download-progress', (p) => {
    sendToRenderer('update-status', {
      state: 'downloading',
      percent: Math.round(p.percent ?? 0),
    })
  })
  autoUpdater.on('update-downloaded', (info) => {
    console.log('[updater] downloaded:', info.version)
    sendToRenderer('update-status', { state: 'downloaded', version: info.version })
  })
  autoUpdater.on('error', (err) => {
    // Networks fail; we don't want this to scare the user. Just log and stay quiet.
    console.warn('[updater] error:', err?.message || err)
    sendToRenderer('update-status', { state: 'error', message: err?.message || String(err) })
  })

  try {
    autoUpdater.checkForUpdates()
  } catch (e) {
    console.warn('[updater] check failed:', e.message)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// IPC
// ─────────────────────────────────────────────────────────────────────────────

ipcMain.handle('select-folder', async () => {
  if (!mainWindow) return null
  const r = await dialog.showOpenDialog(mainWindow, {
    properties: ['openDirectory'],
    title: 'Select the folder ShelfBot should index',
  })
  return r.canceled ? null : r.filePaths[0]
})

// First-run convenience: the common folders where personal documents actually
// live, returned only if they exist on this machine. The onboarding pre-selects
// these so a new user goes from sign-in → indexing in one click instead of
// hunting through a file picker (the single biggest time-to-first-value win).
ipcMain.handle('suggest-folders', () => {
  const out = []
  for (const name of ['downloads', 'documents', 'desktop']) {
    try {
      const p = app.getPath(name)
      if (p && existsSync(p)) out.push(p)
    } catch { /* path not available on this OS — skip */ }
  }
  return out
})

// Opens a source file in the user's default app (Preview, Word, …).
// shell.openPath returns an empty string on success and an error message on failure.
ipcMain.handle('open-path', async (_event, filePath) => {
  if (!filePath || typeof filePath !== 'string') return 'No path provided'
  if (!existsSync(filePath)) return 'File no longer exists at: ' + filePath
  const err = await shell.openPath(filePath)
  return err || ''
})

// ─────────────────────────────────────────────────────────────────────────────
// Calendar reminders — OS-agnostic via the iCalendar (.ics) standard
// ─────────────────────────────────────────────────────────────────────────────
// .ics generation lives in ./ics.js (dependency-free so it's unit-testable).

// Create one or more calendar reminders. payload: { events: [{title, description,
// date 'YYYY-MM-DD', time 'HH:MM', leadDays, recurring, sourceFile}] }.
// Batching many events into one .ics means "Set all" is a single calendar import.
ipcMain.handle('reminder:create', async (_e, payload) => {
  try {
    const events = Array.isArray(payload?.events)
      ? payload.events
      : (payload && payload.date ? [payload] : [])
    if (events.length === 0) return { ok: false, error: 'No events to add' }

    const ics  = buildCalendar(events)
    const file = join(app.getPath('temp'), `rudo-reminder-${Date.now()}.ics`)
    writeFileSync(file, ics, 'utf8')

    const err = await shell.openPath(file)   // '' on success
    if (err) return { ok: false, error: err }
    return { ok: true, count: events.length }
  } catch (e) {
    console.error('[ShelfBot] reminder:create failed:', e.message)
    return { ok: false, error: e.message }
  }
})

// ─────────────────────────────────────────────────────────────────────────────
// File export — "Export to Excel" (CSV) and any future generated downloads
// ─────────────────────────────────────────────────────────────────────────────
// The renderer builds the file contents (it knows the data); we just show a
// native Save dialog and write the bytes locally. Stays fully offline — no
// proxy, no run-cost. payload: { suggestedName, content }.
ipcMain.handle('export:file', async (_e, payload) => {
  try {
    const content = payload?.content
    if (typeof content !== 'string' || content.length === 0) {
      return { ok: false, error: 'Nothing to export' }
    }
    if (!mainWindow) return { ok: false, error: 'No window' }

    const suggested = (typeof payload?.suggestedName === 'string' && payload.suggestedName)
      ? payload.suggestedName
      : `rudo-export-${Date.now()}.csv`

    const r = await dialog.showSaveDialog(mainWindow, {
      title: 'Export to Excel (CSV)',
      defaultPath: join(app.getPath('documents'), suggested),
      filters: [{ name: 'CSV (Comma-separated values)', extensions: ['csv'] }],
    })
    if (r.canceled || !r.filePath) return { ok: false, canceled: true }

    // content already carries a UTF-8 BOM; 'utf8' encodes it as EF BB BF so
    // Excel detects the encoding and renders ₹/non-ASCII correctly.
    writeFileSync(r.filePath, content, 'utf8')
    return { ok: true, path: r.filePath }
  } catch (e) {
    console.error('[ShelfBot] export:file failed:', e.message)
    return { ok: false, error: e.message }
  }
})

// Renders a self-contained HTML report to a PDF via Electron's built-in
// printToPDF (no external dependency, no report persistence — on demand only).
// The HTML is written to a temp file, loaded into an offscreen window, printed,
// and the temp file removed. The user picks the destination via a save dialog.
ipcMain.handle('report:generate', async (_e, payload) => {
  let win = null
  let tmp = null
  try {
    const html = payload?.html
    if (typeof html !== 'string' || html.length === 0) {
      return { ok: false, error: 'Nothing to render' }
    }
    if (!mainWindow) return { ok: false, error: 'No window' }

    const suggested = (typeof payload?.suggestedName === 'string' && payload.suggestedName)
      ? payload.suggestedName
      : `rudo-report-${Date.now()}.pdf`

    const r = await dialog.showSaveDialog(mainWindow, {
      title: 'Export report to PDF',
      defaultPath: join(app.getPath('documents'), suggested),
      filters: [{ name: 'PDF document', extensions: ['pdf'] }],
    })
    if (r.canceled || !r.filePath) return { ok: false, canceled: true }

    tmp = join(app.getPath('temp'), `rudo-report-${Date.now()}-${randomBytes(4).toString('hex')}.html`)
    writeFileSync(tmp, html, 'utf8')

    win = new BrowserWindow({
      show: false,
      width: 900,
      height: 1200,
      webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false, javascript: false },
    })
    await win.loadFile(tmp)

    const pdf = await win.webContents.printToPDF({
      printBackground: true,
      pageSize: 'A4',
    })
    writeFileSync(r.filePath, pdf)
    return { ok: true, path: r.filePath }
  } catch (e) {
    console.error('[ShelfBot] report:generate failed:', e.message)
    return { ok: false, error: e.message }
  } finally {
    if (win) { try { win.destroy() } catch { /* ignore */ } }
    if (tmp) { try { unlinkSync(tmp) } catch { /* ignore */ } }
  }
})

ipcMain.on('window-minimize', () => mainWindow?.minimize())
ipcMain.on('window-maximize', () =>
  mainWindow?.isMaximized() ? mainWindow.unmaximize() : mainWindow?.maximize()
)
ipcMain.on('window-close',    () => mainWindow?.close())
ipcMain.on('open-external',   (_, url) => {
  // Only open web + mail links (the only schemes the app ever sends). Guards
  // against an unexpected value launching some other protocol handler.
  if (typeof url === 'string' && /^(https?|mailto):/i.test(url)) {
    shell.openExternal(url)
  } else {
    console.warn('[ShelfBot] refused to open external URL:', url)
  }
})

// ─────────────────────────────────────────────────────────────────────────────
// Device-identity IPC
// ─────────────────────────────────────────────────────────────────────────────
//
// There's no "login" anymore. On first launch the app auto-registers the
// device with the proxy and stores the returned JWT in safeStorage. Every
// subsequent launch reuses the saved JWT and silently refreshes it if the
// proxy says it's expired.
//
// Renderer-side surface:
//   device:bootstrap → on app load. Ensures the Java backend has a current
//                       JWT pushed to it before any query runs.
//   device:me        → fetch /me (plan + today's usage) for display.
//   device:logout    → clears local token AND device.id, forcing a fresh
//                       registration on next launch. (Mostly for testing.)

let lastApiPort = null

/**
 * Normalizes a /me response. Google accounts return { account, plan, trial,
 * usage }; legacy device tokens return { device:{plan}, usage }. Returns a flat
 * shape the renderer's auth state consumes, so plan / trial / email survive an
 * app restart (this is what surfaces an expired trial after relaunch).
 */
function readMe(me) {
  if (me && me.account) {
    return {
      plan:    me.plan ?? 'trial',
      usage:   me.usage ?? null,
      email:   me.account.email ?? null,
      trial:   me.trial ?? null,
      offline: false,
    }
  }
  return { plan: me?.device?.plan ?? 'free', usage: me?.usage ?? null, email: null, trial: null, offline: false }
}

async function pushTokenToBackend(token) {
  if (!lastApiPort) return
  try {
    await fetch(`http://localhost:${lastApiPort}/api/auth`, {
      method:  'POST',
      headers: { 'Content-Type': 'application/json', 'X-Shelfbot-Token': LOCAL_API_TOKEN },
      body:    JSON.stringify({ token }),
    })
  } catch (e) {
    console.warn('[ShelfBot] could not push token to backend:', e.message)
  }
}

/**
 * Hits /device/register on the proxy, persists the returned JWT, pushes it
 * to the Java backend. Returns {ok, plan, usage} on success.
 */
// NOTE: legacy anonymous device registration — no longer called now that
// Google sign-in is mandatory (see device:bootstrap). Kept for reference /
// possible future "continue without account" path; safe to remove later.
async function registerWithProxy() {
  const deviceId = getDeviceId()
  console.log('[device:register] using deviceId', deviceId.slice(0, 8) + '...(truncated)')
  try {
    const r = await fetch(`${PROXY_URL}/device/register`, {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify({ deviceId }),
    })
    if (!r.ok) {
      const body = await r.json().catch(() => ({}))
      return { ok: false, error: body.error || `HTTP ${r.status}` }
    }
    const body = await r.json()
    persistToken(JSON.stringify({ token: body.token }))
    await pushTokenToBackend(body.token)
    return { ok: true, plan: body.device.plan, usage: body.usage }
  } catch (e) {
    return { ok: false, error: e.message, offline: true }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Google sign-in (PKCE loopback flow)
// ─────────────────────────────────────────────────────────────────────────────
//
// Standard installed-app OAuth, the same pattern Claude Code / gcloud use:
//   1. Generate a PKCE verifier+challenge and a random state.
//   2. Start a one-shot 127.0.0.1 HTTP server on an ephemeral port.
//   3. Open the system browser to Google's consent page (Google forbids
//      embedded webviews) with redirect_uri = our loopback address.
//   4. Google redirects back to the loopback with ?code=…; we capture it.
//   5. Hand {code, codeVerifier, redirectUri} to the proxy, which exchanges it
//      with Google (client secret stays server-side), verifies the id_token,
//      and returns an account-scoped JWT. We persist + push it like any token.

function makePkce() {
  const verifier  = randomBytes(32).toString('base64url')
  const challenge = createHash('sha256').update(verifier).digest('base64url')
  return { verifier, challenge }
}

function googleSignInFlow() {
  return new Promise((resolve) => {
    const { verifier, challenge } = makePkce()
    const state = randomUUID()
    let settled = false
    const done = (result) => { if (!settled) { settled = true; try { server.close() } catch {} resolve(result) } }

    const server = createHttpServer(async (req, res) => {
      const url = new URL(req.url, 'http://127.0.0.1')
      const code = url.searchParams.get('code')
      const err  = url.searchParams.get('error')
      const st   = url.searchParams.get('state')
      if (!code && !err) { res.writeHead(404); res.end(); return }

      res.writeHead(200, { 'Content-Type': 'text/html' })
      res.end('<!doctype html><meta charset="utf-8"><body style="font-family:system-ui;padding:48px;text-align:center">'
        + '<h2>You\'re signed in to Rudo</h2><p>You can close this tab and return to the app.</p></body>')

      if (err)              return done({ ok: false, error: err })
      if (st !== state)     return done({ ok: false, error: 'State mismatch — please try signing in again.' })

      try {
        const redirectUri = `http://127.0.0.1:${server.address().port}`
        const r = await fetch(`${PROXY_URL}/auth/google`, {
          method:  'POST',
          headers: { 'Content-Type': 'application/json' },
          body:    JSON.stringify({ code, codeVerifier: verifier, redirectUri }),
        })
        const body = await r.json().catch(() => ({}))
        if (!r.ok) return done({ ok: false, error: body.error || `Sign-in failed (HTTP ${r.status})` })

        persistToken(JSON.stringify({ token: body.token }))
        await pushTokenToBackend(body.token)
        done({ ok: true, plan: body.account?.plan, usage: body.usage, account: body.account, trial: body.trial })
      } catch (e) {
        done({ ok: false, error: e.message })
      }
    })

    server.on('error', (e) => done({ ok: false, error: 'Could not start local sign-in listener: ' + e.message }))
    server.listen(0, '127.0.0.1', () => {
      const redirectUri = `http://127.0.0.1:${server.address().port}`
      const authUrl = 'https://accounts.google.com/o/oauth2/v2/auth?' + new URLSearchParams({
        client_id:             GOOGLE_CLIENT_ID,
        redirect_uri:          redirectUri,
        response_type:         'code',
        scope:                 'openid email profile',
        code_challenge:        challenge,
        code_challenge_method: 'S256',
        state,
        prompt:                'select_account',
      }).toString()
      shell.openExternal(authUrl)
    })

    // Abandon the attempt if the user never finishes in the browser.
    setTimeout(() => done({ ok: false, error: 'Sign-in timed out. Please try again.' }), 300_000)
  })
}

ipcMain.handle('auth:google-signin', () => googleSignInFlow())

ipcMain.handle('device:bootstrap', async () => {
  console.log('[device:bootstrap] token path:', TOKEN_FILE())
  const saved = loadToken()

  // No token → the user must sign in with Google. We deliberately do NOT
  // auto-register an anonymous device: that handed out free access and bypassed
  // the account + trial model entirely.
  if (!saved?.token) {
    console.log('[device:bootstrap] no saved token — sign-in required')
    return { authenticated: false }
  }

  // Have a token → verify it still works.
  try {
    const r = await fetch(`${PROXY_URL}/me`, {
      headers: { Authorization: `Bearer ${saved.token}` },
    })
    if (r.status === 401 || r.status === 403) {
      console.log('[device:bootstrap] saved token rejected — sign-in required')
      clearToken()
      return { authenticated: false }
    }
    if (!r.ok) {
      // Proxy reachable but errored on /me. Keep the user functional —
      // their next API call will surface the issue if it persists.
      console.log('[device:bootstrap] /me returned', r.status, '— trusting saved token.')
      await pushTokenToBackend(saved.token)
      return { authenticated: true, plan: 'free', usage: null }
    }
    const me = await r.json()
    await pushTokenToBackend(saved.token)
    const info = readMe(me)
    console.log('[device:bootstrap] OK, plan=' + info.plan)
    return { authenticated: true, ...info }
  } catch (e) {
    // Proxy unreachable. Stay signed in optimistically with the cached token.
    console.log('[device:bootstrap] proxy unreachable, trusting saved token:', e.message)
    await pushTokenToBackend(saved.token)
    return { authenticated: true, plan: 'free', usage: null, offline: true }
  }
})

ipcMain.handle('device:me', async () => {
  const saved = loadToken()
  if (!saved?.token) return { authenticated: false }
  try {
    const r = await fetch(`${PROXY_URL}/me`, {
      headers: { Authorization: `Bearer ${saved.token}` },
    })
    if (!r.ok) return { authenticated: false, reason: 'expired' }
    const me = await r.json()
    return { authenticated: true, ...readMe(me) }
  } catch {
    return { authenticated: false, reason: 'unreachable' }
  }
})

ipcMain.handle('device:logout', async () => {
  clearToken()
  // Don't clear device.id — same machine should still get the same identity
  // after a "logout" / re-register. Clearing device.id would let a user
  // reset their free quota by clicking "logout" + reopening the app.
  if (lastApiPort) {
    try { await fetch(`http://localhost:${lastApiPort}/api/auth`, { method: 'DELETE', headers: { 'X-Shelfbot-Token': LOCAL_API_TOKEN } }) } catch {}
  }
  return { ok: true }
})

// Auto-updater IPC: trigger install (with relaunch) or a manual check.
ipcMain.handle('updater:install', () => {
  if (!autoUpdater) return { ok: false, reason: 'unavailable' }
  try {
    // quitAndInstall(isSilent=false, isForceRunAfter=true) — restart the app
    // after installing rather than leaving the user with a quit app.
    autoUpdater.quitAndInstall(false, true)
    return { ok: true }
  } catch (e) {
    return { ok: false, reason: e?.message || 'install failed' }
  }
})
ipcMain.handle('updater:check', async () => {
  if (!autoUpdater) return { ok: false, reason: 'unavailable' }
  try {
    const r = await autoUpdater.checkForUpdates()
    return { ok: true, version: r?.updateInfo?.version }
  } catch (e) {
    return { ok: false, reason: e?.message || 'check failed' }
  }
})
