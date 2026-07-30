import { useEffect, useMemo, useRef, useState } from 'react'
import type { User } from 'firebase/auth'
import { useAuth } from './useAuth'
import { SignIn } from './SignIn'
import { Profile } from './Profile'
import { UpdateBanner } from './UpdateBanner'
import { createNote, deleteNote, saveNote, watchNotes, type Note } from './notes'

const AUTOSAVE_DELAY_MS = 600

export default function App() {
  const { user, loading, refresh } = useAuth()

  return (
    <>
      <UpdateBanner />
      {loading ? (
        <div className="boot">Loading…</div>
      ) : user ? (
        <Notes user={user} onRefresh={refresh} />
      ) : (
        <SignIn />
      )}
    </>
  )
}

function Notes({ user, onRefresh }: { user: User; onRefresh: () => Promise<void> }) {
  const uid = user.uid
  const [notes, setNotes] = useState<Note[] | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [showProfile, setShowProfile] = useState(false)

  useEffect(() => watchNotes(uid, setNotes), [uid])

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (!q || !notes) return notes ?? []
    return notes.filter(
      (n) => n.title.toLowerCase().includes(q) || n.body.toLowerCase().includes(q),
    )
  }, [notes, search])

  const selected = notes?.find((n) => n.id === selectedId) ?? null

  async function onNew() {
    setSelectedId(await createNote(uid))
  }

  async function onDelete(id: string) {
    if (selectedId === id) setSelectedId(null)
    await deleteNote(uid, id)
  }

  return (
    <div className={`app ${selected ? 'editing' : ''}`}>
      <aside className="sidebar">
        <header className="sidebar-head">
          <input
            className="search"
            type="search"
            placeholder="Search notes"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <button className="primary new" onClick={onNew} title="New note">
            +
          </button>
        </header>

        <ul className="note-list">
          {notes === null && <li className="empty">Loading…</li>}
          {notes !== null && filtered.length === 0 && (
            <li className="empty">{search ? 'No matches.' : 'No notes yet — hit + to start.'}</li>
          )}
          {filtered.map((n) => (
            <li key={n.id}>
              <button
                className={`note-row ${n.id === selectedId ? 'active' : ''}`}
                onClick={() => setSelectedId(n.id)}
              >
                <span className="note-title">{n.title.trim() || 'Untitled'}</span>
                <span className="note-preview">
                  {n.pending ? 'Saving…' : n.body.trim().slice(0, 60) || 'Empty note'}
                </span>
                <time>{formatWhen(n.updatedAt)}</time>
              </button>
            </li>
          ))}
        </ul>

        <footer className="sidebar-foot">
          <span className="muted" title={user.email ?? ''}>
            {user.displayName || user.email}
          </span>
          <button className="link" onClick={() => setShowProfile(true)}>Settings</button>
        </footer>
      </aside>

      <main className="editor-pane">
        {selected ? (
          <Editor
            key={selected.id}
            uid={uid}
            note={selected}
            onBack={() => setSelectedId(null)}
            onDelete={() => onDelete(selected.id)}
          />
        ) : (
          <div className="placeholder">Select a note, or create one.</div>
        )}
      </main>

      {showProfile && (
        <Profile user={user} onRefresh={onRefresh} onClose={() => setShowProfile(false)} />
      )}
    </div>
  )
}

function Editor({
  uid,
  note,
  onBack,
  onDelete,
}: {
  uid: string
  note: Note
  onBack: () => void
  onDelete: () => void
}) {
  const [title, setTitle] = useState(note.title)
  const [body, setBody] = useState(note.body)
  const [dirty, setDirty] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  // Adopt changes that arrived from another device, but never clobber what the
  // user is actively typing here.
  useEffect(() => {
    if (dirty) return
    setTitle(note.title)
    setBody(note.body)
  }, [note.title, note.body, dirty])

  // Debounced autosave — no save button, the way a notes app should work.
  useEffect(() => {
    if (!dirty) return
    clearTimeout(timer.current)
    timer.current = setTimeout(() => {
      saveNote(uid, note.id, { title, body }).then(() => setDirty(false))
    }, AUTOSAVE_DELAY_MS)
    return () => clearTimeout(timer.current)
  }, [dirty, title, body, uid, note.id])

  // Flush pending edits when the note is closed or the tab goes away. The latest
  // values live in a ref so this effect never re-runs mid-typing — re-running it
  // would fire the cleanup on every keystroke and defeat the debounce above.
  const latest = useRef({ dirty, title, body })
  latest.current = { dirty, title, body }

  useEffect(() => {
    const flush = () => {
      const { dirty, title, body } = latest.current
      if (dirty) saveNote(uid, note.id, { title, body })
    }
    window.addEventListener('pagehide', flush)
    return () => {
      window.removeEventListener('pagehide', flush)
      flush()
    }
  }, [uid, note.id])

  function edit(fn: () => void) {
    fn()
    setDirty(true)
  }

  return (
    <div className="editor">
      <header className="editor-head">
        <button className="link back" onClick={onBack}>← Notes</button>
        <span className="muted status">{dirty || note.pending ? 'Saving…' : 'Saved'}</span>
        <button className="link danger" onClick={onDelete}>Delete</button>
      </header>

      <input
        className="title-input"
        placeholder="Title"
        value={title}
        onChange={(e) => edit(() => setTitle(e.target.value))}
      />
      <textarea
        className="body-input"
        placeholder="Start writing…"
        value={body}
        onChange={(e) => edit(() => setBody(e.target.value))}
      />
    </div>
  )
}

function formatWhen(d: Date | null): string {
  if (!d) return ''
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  if (sameDay) return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  if (d.getFullYear() === now.getFullYear())
    return d.toLocaleDateString([], { month: 'short', day: 'numeric' })
  return d.toLocaleDateString([], { year: 'numeric', month: 'short', day: 'numeric' })
}
