import { app, BrowserWindow, dialog, ipcMain, safeStorage, shell } from 'electron'
import { join }   from 'path'
import { spawn }  from 'child_process'
import { existsSync, readFileSync, writeFileSync, mkdirSync, unlinkSync } from 'fs'
import { createServer } from 'net'
import { machineIdSync } from 'node-machine-id'
import { randomUUID }    from 'crypto'

// ─────────────────────────────────────────────────────────────────────────────
// Auth: persist proxy JWT in OS-encrypted secure storage
// ─────────────────────────────────────────────────────────────────────────────
//
// safeStorage encrypts with a key managed by the OS keychain (macOS Keychain,
// Windows DPAPI, Linux Secret Service). So even if someone reads the file off
// disk, they get ciphertext — only this app, on this user account, can decrypt.

const PROXY_URL    = process.env.SHELFBOT_PROXY_URL || 'http://localhost:8787'
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
    const port = await findFreePort()
    console.log(`[ShelfBot] Starting Java on port ${port}  (${jar})`)

    javaProcess = spawn('java', ['-Xmx512m', '-jar', jar, '--server', '--port', String(port)], {
      cwd:   BACKEND_ROOT,
      stdio: ['ignore', 'pipe', 'pipe'],
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

  mainWindow.webContents.on('did-finish-load', () => {
    mainWindow.webContents.send('api-port', port)
    mainWindow.show()
  })

  mainWindow.on('closed', () => { mainWindow = null })
}

// ─────────────────────────────────────────────────────────────────────────────
// App lifecycle
// ─────────────────────────────────────────────────────────────────────────────

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

ipcMain.on('window-minimize', () => mainWindow?.minimize())
ipcMain.on('window-maximize', () =>
  mainWindow?.isMaximized() ? mainWindow.unmaximize() : mainWindow?.maximize()
)
ipcMain.on('window-close',    () => mainWindow?.close())
ipcMain.on('open-external',   (_, url) => shell.openExternal(url))

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

async function pushTokenToBackend(token) {
  if (!lastApiPort) return
  try {
    await fetch(`http://localhost:${lastApiPort}/api/auth`, {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
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

ipcMain.handle('device:bootstrap', async () => {
  console.log('[device:bootstrap] token path:', TOKEN_FILE())
  const saved = loadToken()

  // No token yet → auto-register. This is the *only* time we hit
  // /device/register; subsequent launches reuse the saved JWT.
  if (!saved?.token) {
    console.log('[device:bootstrap] no saved token, registering fresh device')
    const reg = await registerWithProxy()
    if (!reg.ok) {
      console.warn('[device:bootstrap] register failed:', reg.error)
      // Offline-tolerant: no token means we can't query yet, but the UI
      // can still show settings and the app isn't bricked. We'll retry
      // on the next /me call or app restart.
      return { authenticated: false, error: reg.error, offline: !!reg.offline }
    }
    return { authenticated: true, plan: reg.plan, usage: reg.usage }
  }

  // Have a token → verify it still works.
  try {
    const r = await fetch(`${PROXY_URL}/me`, {
      headers: { Authorization: `Bearer ${saved.token}` },
    })
    if (r.status === 401 || r.status === 403) {
      console.log('[device:bootstrap] saved token rejected, re-registering')
      clearToken()
      const reg = await registerWithProxy()
      return reg.ok
        ? { authenticated: true, plan: reg.plan, usage: reg.usage }
        : { authenticated: false, error: reg.error }
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
    console.log('[device:bootstrap] OK, plan=' + me.device.plan, 'usage', me.usage)
    return { authenticated: true, plan: me.device.plan, usage: me.usage }
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
    return { authenticated: true, plan: me.device.plan, usage: me.usage }
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
    try { await fetch(`http://localhost:${lastApiPort}/api/auth`, { method: 'DELETE' }) } catch {}
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
