import {
  collection,
  deleteDoc,
  doc,
  onSnapshot,
  orderBy,
  query,
  serverTimestamp,
  setDoc,
  updateDoc,
  Timestamp,
} from 'firebase/firestore'
import { db } from './firebase'

export type Note = {
  id: string
  title: string
  body: string
  updatedAt: Date | null
  /** True while a local write is still queued and not yet acknowledged by the server. */
  pending: boolean
}

/**
 * Notes live under users/{uid}/notes/{noteId} rather than a top-level collection.
 * That keeps the security rules trivial and lets us order by updatedAt without
 * needing a composite index.
 */
function notesCol(uid: string) {
  return collection(db, 'users', uid, 'notes')
}

export function watchNotes(uid: string, onChange: (notes: Note[]) => void) {
  const q = query(notesCol(uid), orderBy('updatedAt', 'desc'))
  return onSnapshot(q, { includeMetadataChanges: true }, (snap) => {
    onChange(
      snap.docs.map((d) => {
        const data = d.data()
        const ts = data.updatedAt
        return {
          id: d.id,
          title: (data.title as string) ?? '',
          body: (data.body as string) ?? '',
          updatedAt: ts instanceof Timestamp ? ts.toDate() : null,
          pending: d.metadata.hasPendingWrites,
        }
      }),
    )
  })
}

export async function createNote(uid: string): Promise<string> {
  const ref = doc(notesCol(uid))
  await setDoc(ref, {
    title: '',
    body: '',
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  })
  return ref.id
}

export function saveNote(uid: string, id: string, fields: { title: string; body: string }) {
  return updateDoc(doc(notesCol(uid), id), { ...fields, updatedAt: serverTimestamp() })
}

export function deleteNote(uid: string, id: string) {
  return deleteDoc(doc(notesCol(uid), id))
}
