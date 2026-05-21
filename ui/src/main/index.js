import { app, BrowserWindow, dialog, ipcMain, shell } from 'electron'
import { join }   from 'path'
import { spawn }  from 'child_process'
import { existsSync } from 'fs'
import { createServer } from 'net'

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
})

app.on('window-all-closed', () => { stopJava(); app.quit() })
app.on('before-quit', stopJava)

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
