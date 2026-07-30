const path = require('node:path')
const { app, shell, ipcMain, BrowserWindow } = require('electron')
const { serve } = require('./server')
const { checkForUpdate, downloadUpdate } = require('./updater')

// In a packaged build the web assets are copied into resources/web; in development
// we point straight at the Vite output next door.
const WEB_ROOT = app.isPackaged
  ? path.join(process.resourcesPath, 'web')
  : path.join(__dirname, '..', 'Website', 'dist')

// Google's OAuth screen refuses to load in anything it recognises as an embedded
// browser, so present as plain Chrome.
const CHROME_UA = app.userAgentFallback.replace(/ (Electron|Sync Notes)\/[\d.]+/g, '')

let appUrl = null

function createWindow() {
  const win = new BrowserWindow({
    width: 1100,
    height: 720,
    minWidth: 480,
    minHeight: 400,
    backgroundColor: '#0f1115',
    autoHideMenuBar: true,
    title: 'Sync Notes',
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, 'preload.js'),
    },
  })

  win.loadURL(appUrl, { userAgent: CHROME_UA })

  win.webContents.setWindowOpenHandler(({ url }) => {
    // The Google sign-in popup has to stay inside the app so it can post its result
    // back through window.opener. Anything else is a real link — hand it to the browser.
    if (url.startsWith('https://sync-notes-c252b.firebaseapp.com/') || url.startsWith('https://accounts.google.com/')) {
      return {
        action: 'allow',
        overrideBrowserWindowOptions: {
          width: 520,
          height: 640,
          autoHideMenuBar: true,
        },
      }
    }
    shell.openExternal(url)
    return { action: 'deny' }
  })

  return win
}

ipcMain.handle('app:version', () => app.getVersion())

ipcMain.handle('update:check', () => checkForUpdate(app.getVersion()))

ipcMain.handle('update:install', async (event, downloadUrl) => {
  // Only ever install something GitHub served us for this repo — the renderer
  // shouldn't be able to talk us into running an arbitrary executable.
  if (!/^https:\/\/github\.com\/justAleks0\/sync-notes\/releases\/download\//.test(downloadUrl)) {
    throw new Error('Refused to download from an unexpected location.')
  }

  const installer = await downloadUpdate(downloadUrl, (percent) => {
    event.sender.send('update:progress', percent)
  })

  // Windows can't overwrite the running app, so hand off and get out of the way.
  shell.openPath(installer)
  setTimeout(() => app.quit(), 1000)
  return true
})

app.whenReady().then(async () => {
  appUrl = await serve(WEB_ROOT)
  createWindow()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
