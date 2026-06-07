import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('electron', {
  selectFolder:   ()     => ipcRenderer.invoke('select-folder'),
  openPath:       (path) => ipcRenderer.invoke('open-path', path),
  minimizeWindow: ()     => ipcRenderer.send('window-minimize'),
  maximizeWindow: ()     => ipcRenderer.send('window-maximize'),
  closeWindow:    ()     => ipcRenderer.send('window-close'),
  openExternal:   (url)  => ipcRenderer.send('open-external', url),
  onApiPort:      (cb)   => ipcRenderer.on('api-port', (_e, p) => cb(p)),
  platform:       process.platform,

  // Calendar reminders (.ics — works on every OS with a calendar app)
  createReminder: (payload) => ipcRenderer.invoke('reminder:create', payload),

  // Auto-updater
  onUpdateStatus:    (cb) => ipcRenderer.on('update-status', (_e, s) => cb(s)),
  installUpdate:     ()   => ipcRenderer.invoke('updater:install'),
  checkForUpdates:   ()   => ipcRenderer.invoke('updater:check'),

  // Device identity (no per-user login — the install IS the identity)
  deviceBootstrap: () => ipcRenderer.invoke('device:bootstrap'),
  deviceMe:        () => ipcRenderer.invoke('device:me'),
  deviceLogout:    () => ipcRenderer.invoke('device:logout'),
})
