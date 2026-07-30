import { useEffect, useMemo, useRef, useState } from 'react'
import type { User } from 'firebase/auth'
import { useAuth } from './useAuth'
import { SignIn } from './SignIn'
import { Profile } from './Profile'
import { UpdateBanner } from './UpdateBanner'
import { Markdown } from './Markdown'
import { imageMarkdown, imageProblem, uploadErrorMessage, uploadImage } from './images'
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
          <span className="account-name muted" title={user.email ?? ''}>
            {user.displayName || user.email}
          </span>
          <span className="version muted" title="Installed version">v{__APP_VERSION__}</span>
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
  const [preview, setPreview] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState('')
  const [dragging, setDragging] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const bodyRef = useRef<HTMLTextAreaElement>(null)
  const fileRef = useRef<HTMLInputElement>(null)

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

  /**
   * Splices text in at the caret, so an image lands where the user was typing.
   * An unfocused textarea reports a caret of 0, which would silently put the image
   * before everything already written — so append instead unless it really has focus.
   */
  function insertAtCaret(text: string) {
    const field = bodyRef.current
    const focused = field !== null && document.activeElement === field
    const start = focused ? field.selectionStart : body.length
    const end = focused ? field.selectionEnd : body.length

    edit(() => setBody(`${body.slice(0, start)}${text}${body.slice(end)}`))

    requestAnimationFrame(() => {
      if (!field) return
      const caret = start + text.length
      field.focus()
      field.setSelectionRange(caret, caret)
    })
  }

  async function addImages(files: File[]) {
    const images = files.filter((f) => f.type.startsWith('image/'))
    if (images.length === 0) return

    setUploadError('')
    for (const file of images) {
      const problem = imageProblem(file)
      if (problem) {
        setUploadError(problem)
        continue
      }
      setUploading(true)
      try {
        const url = await uploadImage(uid, note.id, file)
        insertAtCaret(`\n\n${imageMarkdown(file, url)}\n\n`)
      } catch (err) {
        setUploadError(uploadErrorMessage(err))
      } finally {
        setUploading(false)
      }
    }
  }

  return (
    <div
      className={`editor ${dragging ? 'dropping' : ''}`}
      onDragOver={(e) => {
        if (!e.dataTransfer.types.includes('Files')) return
        e.preventDefault()
        setDragging(true)
      }}
      onDragLeave={(e) => {
        if (e.currentTarget.contains(e.relatedTarget as Node)) return
        setDragging(false)
      }}
      onDrop={(e) => {
        if (!e.dataTransfer.types.includes('Files')) return
        e.preventDefault()
        setDragging(false)
        addImages(Array.from(e.dataTransfer.files))
      }}
    >
      <header className="editor-head">
        <button className="link back" onClick={onBack}>← Notes</button>
        <span className="muted status">
          {uploading ? 'Uploading image…' : dirty || note.pending ? 'Saving…' : 'Saved'}
        </span>
        <button
          className="link"
          onClick={() => fileRef.current?.click()}
          disabled={uploading}
          title="Insert an image — you can also paste or drag one in"
        >
          Image
        </button>
        <button className="link" onClick={() => setPreview(!preview)}>
          {preview ? 'Edit' : 'Preview'}
        </button>
        <button className="link danger" onClick={onDelete}>Delete</button>
      </header>

      <input
        ref={fileRef}
        type="file"
        accept="image/*"
        multiple
        hidden
        onChange={(e) => {
          addImages(Array.from(e.target.files ?? []))
          e.target.value = ''
        }}
      />

      {uploadError && <p className="error upload-error">{uploadError}</p>}

      <input
        className="title-input"
        placeholder="Title"
        value={title}
        onChange={(e) => edit(() => setTitle(e.target.value))}
        readOnly={preview}
      />
      {preview ? (
        body.trim() ? (
          <div className="body-preview">
            <Markdown source={body} />
          </div>
        ) : (
          <div className="body-preview placeholder">Nothing to preview yet.</div>
        )
      ) : (
        <textarea
          ref={bodyRef}
          className="body-input"
          placeholder="Start writing… markdown works: **bold**, # heading, - list"
          value={body}
          onChange={(e) => edit(() => setBody(e.target.value))}
          onPaste={(e) => {
            // Pasting a screenshot should just work, so intercept before the
            // clipboard's text/plain fallback lands in the textarea.
            const files = Array.from(e.clipboardData.files)
            if (files.some((f) => f.type.startsWith('image/'))) {
              e.preventDefault()
              addImages(files)
            }
          }}
        />
      )}

      {dragging && <div className="drop-hint">Drop images to add them</div>}
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
