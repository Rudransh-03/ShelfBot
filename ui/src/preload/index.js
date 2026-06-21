import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('electron', {
  selectFolder:   ()     => ipcRenderer.invoke('select-folder'),
  openPath:       (path) => ipcRenderer.invoke('open-path', path),
  minimizeWindow: ()     => ipcRenderer.send('window-minimize'),
  maximizeWindow: ()     => ipcRenderer.send('window-maximize'),
  closeWindow:    ()     => ipcRenderer.send('window-close'),
  openExternal:   (url)  => ipcRenderer.send('open-external', url),
  onApiPort:      (cb)   => {
    const handler = (_e, p) => cb(p)
    ipcRenderer.on('api-port', handler)
    return () => ipcRenderer.removeListener('api-port', handler)
  },
  platform:       process.platform,

  // Calendar reminders (.ics — works on every OS with a calendar app)
  createReminder: (payload) => ipcRenderer.invoke('reminder:create', payload),

  // Export a generated file (e.g. CSV for Excel) via a native Save dialog.
  exportFile: (payload) => ipcRenderer.invoke('export:file', payload),

  // Auto-updater
  onUpdateStatus:    (cb) => {
    const handler = (_e, s) => cb(s)
    ipcRenderer.on('update-status', handler)
    return () => ipcRenderer.removeListener('update-status', handler)
  },
  installUpdate:     ()   => ipcRenderer.invoke('updater:install'),
  checkForUpdates:   ()   => ipcRenderer.invoke('updater:check'),

  // Device identity (legacy anonymous fallback — the install IS the identity)
  deviceBootstrap: () => ipcRenderer.invoke('device:bootstrap'),
  deviceMe:        () => ipcRenderer.invoke('device:me'),
  deviceLogout:    () => ipcRenderer.invoke('device:logout'),

  // Google sign-in (account identity). Opens the system browser; resolves to
  // { ok, plan, usage, account, trial } or { ok:false, error }.
  googleSignIn:    () => ipcRenderer.invoke('auth:google-signin'),
})
