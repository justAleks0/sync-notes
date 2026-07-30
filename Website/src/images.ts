import { getDownloadURL, getStorage, ref, uploadBytes } from 'firebase/storage'
import { app } from './firebase'

export const storage = getStorage(app)

/** Anything bigger than this is refused before it is uploaded. */
export const MAX_IMAGE_BYTES = 10 * 1024 * 1024

const ALLOWED = ['image/png', 'image/jpeg', 'image/gif', 'image/webp', 'image/svg+xml']

export function imageProblem(file: File): string {
  if (!ALLOWED.includes(file.type)) return `${file.type || 'That file'} isn't a supported image.`
  if (file.size > MAX_IMAGE_BYTES) {
    return `That image is ${(file.size / 1024 / 1024).toFixed(1)} MB — the limit is 10 MB.`
  }
  return ''
}

/**
 * Uploads to users/{uid}/notes/{noteId}/… so an image is owned by exactly one note,
 * which keeps the storage rules a path check in the same shape as Firestore's.
 */
export async function uploadImage(uid: string, noteId: string, file: File): Promise<string> {
  const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, '_') || 'image'
  const path = `users/${uid}/notes/${noteId}/${Date.now()}-${safeName}`
  const storageRef = ref(storage, path)
  await uploadBytes(storageRef, file, { contentType: file.type })
  return getDownloadURL(storageRef)
}

/** Markdown image syntax, with the filename as alt text so it degrades readably. */
export function imageMarkdown(file: File, url: string): string {
  const alt = file.name.replace(/\.[^.]+$/, '').replace(/[[\]]/g, '')
  return `![${alt}](${url})`
}

/** Firebase Storage errors are opaque; the common ones deserve real sentences. */
export function uploadErrorMessage(err: unknown): string {
  const code = (err as { code?: string })?.code ?? ''
  switch (code) {
    case 'storage/unauthorized':
      return "You don't have permission to upload here. Are the storage rules deployed?"
    case 'storage/canceled':
      return ''
    case 'storage/retry-limit-exceeded':
    case 'storage/unknown':
      return 'Upload failed — check your connection and whether Storage is enabled for this project.'
    case 'storage/quota-exceeded':
      return 'Storage quota exceeded for this project.'
    default:
      return (err as Error)?.message ?? 'Upload failed.'
  }
}
