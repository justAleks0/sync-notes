import { useUpdateCheck } from './updates'

/**
 * Renders nothing at all when this build is the newest one — an up-to-date app
 * should be silent.
 */
export function UpdateBanner() {
  const { update, progress, error, install } = useUpdateCheck()
  if (!update) return null

  const downloading = progress !== null

  return (
    <div className="update-banner">
      <div className="update-text">
        <strong>Hey, there's a new update.</strong>
        <span className="muted">
          Version {update.version} is ready — you're on an older build.
        </span>
        {error && <span className="error">{error}</span>}
      </div>

      <button className="update-button" onClick={install} disabled={downloading}>
        {downloading ? `Downloading… ${progress}%` : 'Download now'}
      </button>

      {downloading && (
        <div className="update-progress" role="progressbar" aria-valuenow={progress ?? 0}>
          <div className="update-progress-fill" style={{ width: `${progress}%` }} />
        </div>
      )}
    </div>
  )
}
