import { useEffect, useState } from 'react'
import { Markdown } from './Markdown'
import { watchRevisions, type Revision } from './revisions'

/** "2 minutes ago" reads better than a timestamp for anything from today. */
export function relativeTime(at: Date | null): string {
  if (!at) return 'just now'

  const seconds = Math.round((Date.now() - at.getTime()) / 1000)
  if (seconds < 60) return 'just now'

  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? '' : 's'} ago`

  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} hour${hours === 1 ? '' : 's'} ago`

  const days = Math.round(hours / 24)
  if (days < 7) return `${days} day${days === 1 ? '' : 's'} ago`

  return at.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

/** How this version differs in size from the one that came after it. */
function sizeDelta(revision: Revision, newer: Revision | undefined): string {
  const previousLength = newer ? newer.body.length : revision.body.length
  const delta = revision.body.length - previousLength
  if (delta === 0) return ''
  return delta > 0 ? `+${delta}` : `${delta}`
}

type Props = {
  uid: string
  noteId: string
  /** What the note says right now, to mark the live version in the list. */
  current: { title: string; body: string }
  onRestore: (revision: Revision) => void
  onClose: () => void
}

export function HistoryPanel({ uid, noteId, current, onRestore, onClose }: Props) {
  const [revisions, setRevisions] = useState<Revision[] | null>(null)
  const [selected, setSelected] = useState<Revision | null>(null)

  useEffect(() => {
    setSelected(null)
    return watchRevisions(uid, noteId, setRevisions)
  }, [uid, noteId])

  if (selected) {
    const unchanged = selected.title === current.title && selected.body === current.body

    return (
      <div className="history-panel">
        <header className="history-head">
          <button className="link" onClick={() => setSelected(null)}>← All versions</button>
          <span className="muted">{relativeTime(selected.at)}</span>
          <button className="link" onClick={onClose}>Close</button>
        </header>

        <div className="history-preview">
          <h3>{selected.title || 'Untitled'}</h3>
          {selected.body.trim() ? (
            <Markdown source={selected.body} />
          ) : (
            <p className="muted">This version was empty.</p>
          )}
        </div>

        <footer className="history-foot">
          {/* Restoring writes the note, so the version you are leaving is itself
              checkpointed first — a restore can always be undone by restoring
              the entry it creates. */}
          <button className="primary" disabled={unchanged} onClick={() => onRestore(selected)}>
            {unchanged ? 'This is the current version' : 'Restore this version'}
          </button>
        </footer>
      </div>
    )
  }

  const liveIndex = (revisions ?? []).findIndex(
    (r) => r.title === current.title && r.body === current.body,
  )

  return (
    <div className="history-panel">
      <header className="history-head">
        <strong>History</strong>
        <span className="muted">
          {revisions === null ? 'Loading…' : `${revisions.length} saved version${revisions.length === 1 ? '' : 's'}`}
        </span>
        <button className="link" onClick={onClose}>Close</button>
      </header>

      {revisions !== null && revisions.length === 0 && (
        <p className="muted">
          No versions yet. One is kept every couple of minutes while you write, and
          whenever a lot changes at once.
        </p>
      )}

      <ul className="history-list">
        {(revisions ?? []).map((revision, i) => {
          // Restoring an old version makes its text current again, so several
          // entries can match what is on screen. Only the newest of them is the
          // one you are actually looking at.
          const live = i === liveIndex
          const delta = sizeDelta(revision, revisions?.[i - 1])

          return (
            <li key={revision.id}>
              <button className="history-item" onClick={() => setSelected(revision)}>
                <span className="history-when">
                  {relativeTime(revision.at)}
                  {live && <span className="history-live"> · current</span>}
                </span>
                <span className="history-summary">
                  {revision.title || 'Untitled'}
                  {delta && <span className="muted"> · {delta} chars</span>}
                </span>
              </button>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
