import { app, BrowserWindow, dialog, ipcMain, safeStorage, shell } from 'electron'
import { join }   from 'path'
import { spawn }  from 'child_process'
import { existsSync, readFileSync, writeFileSync, mkdirSync, unlinkSync } from 'fs'
import { createServer } from 'net'

// ─────────────────────────────────────────────────────────────────────────────
// Auth: persist proxy JWT in OS-encrypted secure storage
// ─────────────────────────────────────────────────────────────────────────────
//
// safeStorage encrypts with a key managed by the OS keychain (macOS Keychain,
// Windows DPAPI, Linux Secret Service). So even if someone reads the file off
// disk, they get ciphertext — only this app, on this user account, can decrypt.

const PROXY_URL  = process.env.SHELFBOT_PROXY_URL || 'http://localhost:8787'
const TOKEN_FILE = () => join(app.getPath('userData'), 'auth.token')

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
  console.log('[ShelfBot] token file would be:', TOKEN_FILE())
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
// Auth IPC
// ─────────────────────────────────────────────────────────────────────────────
//
// Renderer-side flow:
//   1. On boot: call `auth:bootstrap` → main loads token, validates with
//      proxy /me, returns {authenticated, email, usage} (or null if no token).
//   2. Login screen: call `auth:login` with email → main hits proxy
//      /auth/login → receives JWT → persists via safeStorage → pushes to
//      the Java backend's /api/auth → returns success.
//   3. Logout: call `auth:logout` → clears local store + clears Java backend.

let lastApiPort = null

async function pushTokenToBackend(token, email) {
  if (!lastApiPort) return // backend not ready yet; will be pushed by renderer instead
  try {
    await fetch(`http://localhost:${lastApiPort}/api/auth`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token, email }),
    })
  } catch (e) {
    console.warn('[ShelfBot] could not push token to backend:', e.message)
  }
}

ipcMain.handle('auth:bootstrap', async () => {
  const saved = loadToken()
  console.log('[auth:bootstrap] token file exists:', !!saved?.token,
              '  storage path:', TOKEN_FILE())
  if (!saved?.token) return { authenticated: false }

  // Verify against the proxy. If it explicitly rejects the token (401/403),
  // we clear and force a fresh login. If it's *unreachable* (proxy not yet
  // started, network blip, offline), we optimistically trust the saved token
  // — the user shouldn't be kicked back to the login screen just because
  // the proxy is slow to come up. First real API call will surface the
  // problem if the token is actually bad.
  try {
    const r = await fetch(`${PROXY_URL}/me`, {
      headers: { Authorization: `Bearer ${saved.token}` },
    })
    if (r.status === 401 || r.status === 403) {
      console.log('[auth:bootstrap] proxy rejected token (', r.status, '), clearing.')
      clearToken()
      return { authenticated: false, reason: 'expired' }
    }
    if (!r.ok) {
      // Other status — proxy is up but something else is wrong. Don't kick
      // the user out; surface the cached email so the main UI still loads.
      console.log('[auth:bootstrap] /me returned', r.status, '— trusting saved token.')
      await pushTokenToBackend(saved.token, saved.email)
      return { authenticated: true, email: saved.email, usage: null }
    }
    const me = await r.json()
    await pushTokenToBackend(saved.token, me.user.email)
    console.log('[auth:bootstrap] OK,', me.user.email, 'usage', me.usage)
    return { authenticated: true, email: me.user.email, usage: me.usage }
  } catch (e) {
    // Proxy unreachable — keep the user signed in optimistically.
    console.log('[auth:bootstrap] proxy unreachable (', e.message, '), trusting saved token.')
    await pushTokenToBackend(saved.token, saved.email)
    return { authenticated: true, email: saved.email, usage: null, offline: true }
  }
})

ipcMain.handle('auth:login', async (_evt, { email }) => {
  try {
    const r = await fetch(`${PROXY_URL}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email }),
    })
    const body = await r.json().catch(() => ({}))
    if (!r.ok) return { ok: false, error: body.error || `HTTP ${r.status}` }

    persistToken(JSON.stringify({ token: body.token, email: body.user.email }))
    await pushTokenToBackend(body.token, body.user.email)
    return { ok: true, email: body.user.email }
  } catch (e) {
    return { ok: false, error: e.message }
  }
})

ipcMain.handle('auth:logout', async () => {
  clearToken()
  if (lastApiPort) {
    try {
      await fetch(`http://localhost:${lastApiPort}/api/auth`, { method: 'DELETE' })
    } catch {}
  }
  return { ok: true }
})

ipcMain.handle('auth:me', async () => {
  const saved = loadToken()
  if (!saved?.token) return { authenticated: false }
  try {
    const r = await fetch(`${PROXY_URL}/me`, {
      headers: { Authorization: `Bearer ${saved.token}` },
    })
    if (!r.ok) return { authenticated: false, reason: 'expired' }
    const me = await r.json()
    return { authenticated: true, email: me.user.email, usage: me.usage }
  } catch (e) {
    return { authenticated: false, reason: 'unreachable' }
  }
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
