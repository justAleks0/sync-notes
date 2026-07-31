import {
  addDoc,
  collection,
  deleteDoc,
  doc,
  getDocs,
  limit,
  onSnapshot,
  orderBy,
  query,
  serverTimestamp,
  Timestamp,
} from 'firebase/firestore'
import { db } from './firebase'

export type Revision = {
  id: string
  title: string
  body: string
  at: Date | null
}

/**
 * How often the note is worth snapshotting while someone is typing. Autosave runs
 * every 600ms of quiet, and a revision per keystroke-pause would be thousands of
 * useless entries — the history has to read like a list of moments, not a keylog.
 */
const CHECKPOINT_GAP_MS = 2 * 60 * 1000

/** …unless this much text appeared or vanished, which is worth keeping regardless. */
const BIG_CHANGE_CHARS = 200

/** Older revisions are dropped past this. Fifty covers days of real editing. */
export const KEEP_REVISIONS = 50

function revisionsCol(uid: string, noteId: string) {
  return collection(db, 'users', uid, 'notes', noteId, 'revisions')
}

function toRevision(id: string, data: Record<string, unknown>): Revision {
  const at = data.at
  return {
    id,
    title: (data.title as string) ?? '',
    body: (data.body as string) ?? '',
    at: at instanceof Timestamp ? at.toDate() : null,
  }
}

/**
 * Live list of a note's revisions, newest first.
 *
 * Pruning happens here rather than on write: the snapshot has already paid for
 * these documents, so trimming the tail costs nothing extra. A note whose history
 * is never opened keeps growing, which is the trade — and at one revision per two
 * minutes of active typing, that is a slow leak, not a flood.
 */
export function watchRevisions(
  uid: string,
  noteId: string,
  onChange: (revisions: Revision[]) => void,
) {
  const q = query(revisionsCol(uid, noteId), orderBy('at', 'desc'), limit(KEEP_REVISIONS + 25))

  return onSnapshot(q, (snap) => {
    // A pending serverTimestamp reads as null, which would sort the revision you
    // just created to the bottom. The estimate keeps it where it belongs.
    const all = snap.docs.map((d) => toRevision(d.id, d.data({ serverTimestamps: 'estimate' })))
    onChange(all.slice(0, KEEP_REVISIONS))

    for (const stale of snap.docs.slice(KEEP_REVISIONS)) {
      deleteDoc(stale.ref).catch(() => {
        // Losing the race to another device's prune is the expected failure here.
      })
    }
  })
}

/**
 * The newest revision per note, so the common case — deciding not to write one —
 * costs no reads at all. Undefined means "not looked up yet", null means "looked
 * up, there are none".
 */
const newest = new Map<string, Revision | null>()

const cacheKey = (uid: string, noteId: string) => `${uid}/${noteId}`

async function newestRevision(uid: string, noteId: string): Promise<Revision | null> {
  const key = cacheKey(uid, noteId)
  const cached = newest.get(key)
  if (cached !== undefined) return cached

  const snap = await getDocs(query(revisionsCol(uid, noteId), orderBy('at', 'desc'), limit(1)))
  const first = snap.docs[0]
  const found = first ? toRevision(first.id, first.data({ serverTimestamps: 'estimate' })) : null
  newest.set(key, found)
  return found
}

function worthKeeping(last: Revision | null, next: { title: string; body: string }): boolean {
  // Nothing recorded yet: this is the note's starting point, and losing it would
  // make the first session of editing unrecoverable.
  if (last === null) return true
  if (last.title === next.title && last.body === next.body) return false

  const elapsed = last.at ? Date.now() - last.at.getTime() : Infinity
  if (elapsed >= CHECKPOINT_GAP_MS) return true

  return Math.abs(next.body.length - last.body.length) >= BIG_CHANGE_CHARS
}

/**
 * Offers the current text as a checkpoint. Most calls do nothing — that is the
 * point. Pass [always] for moments the user would expect to find in the list no
 * matter the timing, like the state just before a restore.
 */
export async function recordRevision(
  uid: string,
  noteId: string,
  current: { title: string; body: string },
  always = false,
): Promise<void> {
  const last = await newestRevision(uid, noteId)
  if (!always && !worthKeeping(last, current)) return
  // Two identical entries in a row are noise whatever the reason for the call.
  if (last && last.title === current.title && last.body === current.body) return

  const written = await addDoc(revisionsCol(uid, noteId), {
    title: current.title,
    body: current.body,
    at: serverTimestamp(),
  })

  newest.set(cacheKey(uid, noteId), {
    id: written.id,
    ...current,
    // The server's value has not come back yet; for the gap check, now is close
    // enough and always errs towards waiting longer before the next checkpoint.
    at: new Date(),
  })
}

/**
 * Firestore does not delete subcollections with their parent, so a deleted note
 * would otherwise leave its history behind forever, unreachable and unbilled to
 * anyone's attention.
 */
export async function deleteRevisions(uid: string, noteId: string): Promise<void> {
  newest.delete(cacheKey(uid, noteId))
  const snap = await getDocs(revisionsCol(uid, noteId))
  await Promise.all(snap.docs.map((d) => deleteDoc(doc(revisionsCol(uid, noteId), d.id))))
}
