const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
}

/**
 * Ports tried in order, so the app keeps the same origin from one launch to the next.
 *
 * This list is the whole point of the code below. localStorage, IndexedDB, and
 * therefore both the saved API key and the Firebase sign-in, are scoped to an
 * origin — and an origin includes its port. Serving on an OS-assigned port meant
 * every launch woke up somewhere new, with no key and no session, which is
 * exactly as tedious as it sounds. The spares are only for when something else
 * on the machine already holds the first.
 */
const PREFERRED_PORTS = [47317, 47318, 47319, 47320, 47321]

function tryListen(server, port) {
  return new Promise((resolve, reject) => {
    const onError = (err) => {
      server.removeListener('listening', onListening)
      reject(err)
    }
    const onListening = () => {
      server.removeListener('error', onError)
      resolve()
    }

    server.once('error', onError)
    server.once('listening', onListening)
    server.listen(port, '127.0.0.1')
  })
}

/**
 * Serves the built web app from a loopback HTTP server.
 *
 * Why not load dist/index.html over file:// ? Firebase Auth only allows OAuth
 * flows from an authorized domain, and "localhost" is authorized by default
 * while "file://" has no origin at all. Serving over loopback means the desktop
 * app gets the same auth and storage behaviour as the website, offline included.
 */
async function serve(rootDir) {
  const server = http.createServer((req, res) => {
    const urlPath = decodeURIComponent(new URL(req.url, 'http://localhost').pathname)
    let filePath = path.join(rootDir, urlPath)

    // Contain the resolved path inside rootDir, and fall back to the SPA entry point.
    if (!filePath.startsWith(rootDir) || !fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
      filePath = path.join(rootDir, 'index.html')
    }

    res.writeHead(200, {
      'Content-Type': MIME[path.extname(filePath).toLowerCase()] ?? 'application/octet-stream',
    })
    fs.createReadStream(filePath).pipe(res)
  })

  for (const port of PREFERRED_PORTS) {
    try {
      await tryListen(server, port)
      return `http://localhost:${port}`
    } catch (err) {
      if (err.code !== 'EADDRINUSE') throw err
    }
  }

  // Every preferred port is taken. Falling back to any free one keeps the app
  // usable, at the cost of this launch looking like a new origin — the user will
  // have to sign in and re-enter their key. Better than refusing to start.
  await tryListen(server, 0)
  return `http://localhost:${server.address().port}`
}

module.exports = { serve }
