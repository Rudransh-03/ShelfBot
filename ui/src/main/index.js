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

// Per-launch secret shared only between this main process, the Java backend
// (passed via env), and our own renderer (passed over IPC). The backend rejects
// any /api/* request that doesn't echo it in X-Shelfbot-Token — so a malicious
// web page in the user's browser can reach the localhost port but can't read
// their private documents/chat, because it can't know this value.
const LOCAL_API_TOKEN = randomUUID()

let mainWindow  = null
let javaProcess = null

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

  // Don't kill our own child (it shouldn't exist this early, but safe-guard).
  pids = pids.filter(p => !javaProcess || javaProcess.pid !== p)
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
  const lockFile = join(BACKEND_ROOT, 'shelfbot-vector-index', 'write.lock')
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

function startJavaBackend() {
  const jar = getJarPath()
  if (!jar) {
    console.error('[ShelfBot] JAR not found — run `mvn package` inside backend/')
    return Promise.resolve(DEFAULT_PORT)
  }

  return new Promise(async (resolve) => {
    // Always sweep orphaned JVMs first — see killOrphanedJava() for why.
    await killOrphanedJava()
    const port = await findFreePort()
    const javaBin = getJavaBin()
    console.log(`[ShelfBot] Starting Java on port ${port}  (${javaBin} -jar ${jar})`)

    // -Djava.awt.headless=true keeps the JVM from initializing AWT — which
    //   (a) prevents the Java dock/menu icon appearing on macOS, and
    //   (b) avoids loading the windowing system at all on headless Linux servers.
    // Tika's OCR + PDF image parsers pull AWT in transitively, so without this
    //   the user sees a Java icon pop into their dock alongside ShelfBot.
    javaProcess = spawn(javaBin, ['-Djava.awt.headless=true', '-Xmx512m', '-jar', jar, '--server', '--port', String(port)], {
      cwd:   BACKEND_ROOT,
      stdio: ['ignore', 'pipe', 'pipe'],
      // Hand the backend the per-launch API secret + the resolved proxy URL.
      // Spread process.env so the JVM still inherits PATH etc.; passing via env
      // (not argv) keeps the secret out of `ps`/pgrep output. SHELFBOT_PROXY_URL
      // is set explicitly (after the spread) so the backend points at the SAME
      // proxy this app resolved — including the production URL, which no shipped
      // config.properties would otherwise provide.
      env:   { ...process.env, SHELFBOT_LOCAL_TOKEN: LOCAL_API_TOKEN, SHELFBOT_PROXY_URL: PROXY_URL },
    })

    const timer = setTimeout(() => {
      console.warn('[ShelfBot] Backend start timed out — opening UI anyway')
      resolve(port)
    }, START_TIMEOUT)

    function checkReady(data) {
      const text = data.toString()
      process.stdout.write('[Java] ' + text)
      const m = text.match(/SHELFBOT_SERVER_READY:(\d+)/)
      if (m) { clearTimeout(timer); resolve(parseInt(m[1], 10)) }
    }

    javaProcess.stdout.on('data', checkReady)
    javaProcess.stderr.on('data', data => {
      process.stderr.write('[Java/err] ' + data.toString())
      checkReady(data)   // READY line sometimes lands on stderr via Logback
    })

    javaProcess.on('error', err => {
      clearTimeout(timer)
      console.error('[ShelfBot] spawn error:', err.message)
      resolve(port)
    })
  })
}

function stopJava() {
  javaProcess?.kill('SIGTERM')
  javaProcess = null
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
    backgroundColor: '#0b0b14',
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
    mainWindow.webContents.send('api-port', { port, token: LOCAL_API_TOKEN })
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
    console.log('[ShelfBot] token file:', TOKEN_FILE())
    console.log('[ShelfBot] device file:', DEVICE_FILE())
    console.log('[ShelfBot] safeStorage available:', safeStorage.isEncryptionAvailable())
    console.log('[ShelfBot] proxy url:', PROXY_URL)
    const port = await startJavaBackend()
    lastApiPort = port
    createWindow(port)
    // Defer the update check so the window paints first and the user isn't
    // staring at a hanging window while we hit GitHub.
    setTimeout(initAutoUpdater, 4_000)
  })

  app.on('window-all-closed', () => { stopJava(); app.quit() })
  app.on('before-quit', stopJava)
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
