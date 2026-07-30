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
 * Serves the built web app from a loopback HTTP server on a random free port.
 *
 * Why not load dist/index.html over file:// ? Firebase Auth only allows OAuth
 * flows from an authorized domain, and "localhost" is authorized by default
 * while "file://" has no origin at all. Serving over loopback means the desktop
 * app gets the same auth and storage behaviour as the website, offline included.
 */
function serve(rootDir) {
  return new Promise((resolve, reject) => {
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

    server.on('error', reject)
    // Port 0 = let the OS pick a free one, so two launches never collide.
    server.listen(0, '127.0.0.1', () => resolve(`http://localhost:${server.address().port}`))
  })
}

module.exports = { serve }
