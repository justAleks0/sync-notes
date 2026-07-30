import { useCallback, useEffect, useState } from 'react'

export type UpdateInfo = {
  version: string
  downloadUrl: string
  size: number
  releaseUrl: string
}

/**
 * Exposed by the Electron preload script. Absent when the app is running as a plain
 * website — a website is always the newest version the moment you load it, so there
 * is nothing to check and nothing to install.
 */
type SyncNotesBridge = {
  platform: 'desktop'
  deviceLabel: string
  getVersion: () => Promise<string>
  checkForUpdate: () => Promise<UpdateInfo | null>
  installUpdate: (downloadUrl: string) => Promise<boolean>
  onDownloadProgress: (callback: (percent: number) => void) => () => void
}

declare global {
  interface Window {
    syncNotes?: SyncNotesBridge
  }
}

export const bridge = (): SyncNotesBridge | undefined => window.syncNotes

// Re-check periodically so a long-running window still notices a release.
const RECHECK_INTERVAL_MS = 6 * 60 * 60 * 1000

export function useUpdateCheck() {
  const [update, setUpdate] = useState<UpdateInfo | null>(null)
  const [version, setVersion] = useState('')
  const [progress, setProgress] = useState<number | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    const api = bridge()
    if (!api) return

    api.getVersion().then(setVersion)

    let cancelled = false
    const check = () => {
      api.checkForUpdate()
        .then((result) => { if (!cancelled) setUpdate(result) })
        .catch(() => { /* never bother the user about a failed check */ })
    }

    check()
    const timer = setInterval(check, RECHECK_INTERVAL_MS)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [])

  const install = useCallback(async () => {
    const api = bridge()
    if (!api || !update) return

    setError('')
    setProgress(0)
    const stopListening = api.onDownloadProgress(setProgress)
    try {
      await api.installUpdate(update.downloadUrl)
      // The app quits from here — the installer takes over.
    } catch (err) {
      setError((err as Error)?.message ?? 'Download failed.')
      setProgress(null)
    } finally {
      stopListening()
    }
  }, [update])

  return { update, version, progress, error, install }
}
