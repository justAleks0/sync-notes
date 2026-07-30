const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { Readable } = require('node:stream')
const { pipeline } = require('node:stream/promises')

const REPO = 'justAleks0/sync-notes'
const LATEST_RELEASE = `https://api.github.com/repos/${REPO}/releases/latest`

// GitHub rejects API calls without one.
const HEADERS = {
  'User-Agent': 'SyncNotes-Desktop',
  Accept: 'application/vnd.github+json',
}

/**
 * Compares two dotted version strings. Returns >0 if a is newer than b.
 * Anything non-numeric (a "-beta" suffix) is ignored — releases are plain x.y.z.
 */
function compareVersions(a, b) {
  const parse = (v) => String(v).replace(/^v/, '').split('.').map((n) => parseInt(n, 10) || 0)
  const left = parse(a)
  const right = parse(b)
  for (let i = 0; i < Math.max(left.length, right.length); i++) {
    const diff = (left[i] ?? 0) - (right[i] ?? 0)
    if (diff !== 0) return diff
  }
  return 0
}

/**
 * Asks GitHub whether this build is the newest one published for Windows.
 * Resolves to null when we're current, or there's no release, or the network is
 * down — an update check must never be something the user has to deal with.
 */
async function checkForUpdate(currentVersion) {
  try {
    const res = await fetch(LATEST_RELEASE, { headers: HEADERS })
    if (!res.ok) return null

    const release = await res.json()
    const latest = String(release.tag_name ?? '').replace(/^v/, '')
    if (!latest || compareVersions(latest, currentVersion) <= 0) return null

    const asset = (release.assets ?? []).find((a) => a.name.toLowerCase().endsWith('.exe'))
    if (!asset) return null

    return {
      version: latest,
      downloadUrl: asset.browser_download_url,
      size: asset.size,
      releaseUrl: release.html_url,
    }
  } catch {
    return null
  }
}

/**
 * Downloads the installer and hands it to Windows to run. The app has to quit for
 * the installer to replace its own files, which the caller does once this resolves.
 */
async function downloadUpdate(downloadUrl, onProgress) {
  const res = await fetch(downloadUrl, { headers: HEADERS, redirect: 'follow' })
  if (!res.ok) throw new Error(`Download failed (${res.status})`)

  const total = Number(res.headers.get('content-length')) || 0
  let received = 0

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sync-notes-update-'))
  const file = path.join(dir, 'SyncNotesSetup.exe')

  const body = Readable.fromWeb(res.body)
  body.on('data', (chunk) => {
    received += chunk.length
    if (total) onProgress(Math.round((received / total) * 100))
  })

  await pipeline(body, fs.createWriteStream(file))
  return file
}

module.exports = { checkForUpdate, downloadUpdate, compareVersions, REPO }
