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

  // Auto-updater
  onUpdateStatus:    (cb) => ipcRenderer.on('update-status', (_e, s) => cb(s)),
  installUpdate:     ()   => ipcRenderer.invoke('updater:install'),
  checkForUpdates:   ()   => ipcRenderer.invoke('updater:check'),

  // Auth
  authBootstrap:  ()       => ipcRenderer.invoke('auth:bootstrap'),
  authLogin:      (email)  => ipcRenderer.invoke('auth:login', { email }),
  authLogout:     ()       => ipcRenderer.invoke('auth:logout'),
  authMe:         ()       => ipcRenderer.invoke('auth:me'),
})
