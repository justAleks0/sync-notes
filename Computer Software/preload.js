const { contextBridge, ipcRenderer } = require('electron')

// The only surface the web app gets. Everything here is a request the main process
// validates — the renderer can't reach Node or the filesystem directly.
contextBridge.exposeInMainWorld('syncNotes', {
  platform: 'desktop',
  deviceLabel: 'Windows desktop',
  getVersion: () => ipcRenderer.invoke('app:version'),
  checkForUpdate: () => ipcRenderer.invoke('update:check'),
  installUpdate: (downloadUrl) => ipcRenderer.invoke('update:install', downloadUrl),
  onDownloadProgress: (callback) => {
    const listener = (_event, percent) => callback(percent)
    ipcRenderer.on('update:progress', listener)
    return () => ipcRenderer.removeListener('update:progress', listener)
  },
})
