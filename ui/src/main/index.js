import { app, BrowserWindow, dialog, ipcMain, shell } from 'electron'
import { join }   from 'path'
import { spawn }  from 'child_process'
import { existsSync } from 'fs'
import { createServer } from 'net'

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
  const port = await startJavaBackend()
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
