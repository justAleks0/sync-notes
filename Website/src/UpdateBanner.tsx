import { useUpdateCheck } from './updates'

const formatSize = (bytes: number) =>
  bytes > 0 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : ''

/**
 * Renders nothing at all when this build is the newest one — an up-to-date app
 * should be silent. A floating card rather than a full-width bar, which reads
 * like an error message.
 */
export function UpdateBanner() {
  const { update, progress, error, install } = useUpdateCheck()
  if (!update) return null

  const downloading = progress !== null

  return (
    <div className="update-card">
      <span className="update-icon" aria-hidden="true">↓</span>

      <div className="update-text">
        <strong>Hey, there's a new update.</strong>
        <span className="muted">
          Version {update.version} &nbsp;·&nbsp; {formatSize(update.size)}
        </span>
        {error && <span className="error">{error}</span>}
      </div>

      <button className="update-button" onClick={install} disabled={downloading}>
        {downloading ? `${progress}%` : 'Download now'}
      </button>

      {downloading && (
        <div className="update-progress" role="progressbar" aria-valuenow={progress ?? 0}>
          <div className="update-progress-fill" style={{ width: `${progress}%` }} />
        </div>
      )}
    </div>
  )
}
