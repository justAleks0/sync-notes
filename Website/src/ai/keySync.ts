import { deleteDoc, doc, getDoc, setDoc } from 'firebase/firestore'
import { db } from '../firebase'
import type { ProviderId } from './settings'

/**
 * Optional, off by default: keep an encrypted copy of the API key in the account
 * so a new device can unlock it instead of being handed the key again.
 *
 * The encryption is done with a passphrase the user chooses, and that choice is
 * the whole point. Anything the app could decrypt on its own — a key derived from
 * the uid, a secret shipped in the bundle, a value stored beside the ciphertext —
 * would also be decryptable by anyone who reached the account, which is precisely
 * the risk that kept the key out of Firestore in the first place. A passphrase we
 * never store is the only version of this feature that is worth the name.
 *
 * The cost is honest and is spelled out to the user before they turn it on: the
 * passphrase is unrecoverable, and a weak one makes the whole thing weak.
 */
export type EncryptedKey = {
  /** Format version, so this can be changed later without silent breakage. */
  v: 1
  provider: ProviderId
  model: string
  /** Base64. */
  salt: string
  iv: string
  ct: string
  iterations: number
}

/**
 * OWASP's floor for PBKDF2-HMAC-SHA256. Deliberately slow: it is the only thing
 * standing between a weak passphrase and an offline guessing attack on ciphertext
 * that an attacker may already hold.
 */
const ITERATIONS = 310_000

const enc = new TextEncoder()
const dec = new TextDecoder()

const toBase64 = (bytes: ArrayBuffer | Uint8Array) =>
  btoa(String.fromCharCode(...new Uint8Array(bytes)))

const fromBase64 = (text: string) =>
  Uint8Array.from(atob(text), (c) => c.charCodeAt(0))

async function deriveAesKey(
  passphrase: string,
  salt: Uint8Array,
  iterations: number,
): Promise<CryptoKey> {
  const material = await crypto.subtle.importKey(
    'raw',
    enc.encode(passphrase),
    'PBKDF2',
    false,
    ['deriveKey'],
  )

  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt: salt as BufferSource, iterations, hash: 'SHA-256' },
    material,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  )
}

export async function encryptKey(
  apiKey: string,
  passphrase: string,
  provider: ProviderId,
  model: string,
): Promise<EncryptedKey> {
  const salt = crypto.getRandomValues(new Uint8Array(16))
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const key = await deriveAesKey(passphrase, salt, ITERATIONS)

  const ct = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: iv as BufferSource },
    key,
    enc.encode(apiKey),
  )

  return {
    v: 1,
    provider,
    model,
    salt: toBase64(salt),
    iv: toBase64(iv),
    ct: toBase64(ct),
    iterations: ITERATIONS,
  }
}

/**
 * Returns null when the passphrase is wrong.
 *
 * AES-GCM authenticates the ciphertext, so a wrong passphrase fails to decrypt
 * rather than yielding plausible rubbish — the user gets told they mistyped
 * instead of quietly ending up with a broken key.
 */
export async function decryptKey(
  blob: EncryptedKey,
  passphrase: string,
): Promise<string | null> {
  try {
    const key = await deriveAesKey(passphrase, fromBase64(blob.salt), blob.iterations)
    const plain = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: fromBase64(blob.iv) as BufferSource },
      key,
      fromBase64(blob.ct) as BufferSource,
    )
    return dec.decode(plain)
  } catch {
    return null
  }
}

/**
 * One document per account, not per device: the point is that every device sees
 * the same envelope.
 */
function keyDoc(uid: string) {
  return doc(db, 'users', uid, 'private', 'aiKey')
}

export async function loadSyncedKey(uid: string): Promise<EncryptedKey | null> {
  const snap = await getDoc(keyDoc(uid))
  if (!snap.exists()) return null

  const data = snap.data() as Partial<EncryptedKey>
  if (data.v !== 1 || !data.salt || !data.iv || !data.ct) return null

  return {
    v: 1,
    provider: data.provider === 'openai' ? 'openai' : 'anthropic',
    model: typeof data.model === 'string' ? data.model : '',
    salt: data.salt,
    iv: data.iv,
    ct: data.ct,
    iterations: typeof data.iterations === 'number' ? data.iterations : ITERATIONS,
  }
}

export async function saveSyncedKey(uid: string, blob: EncryptedKey): Promise<void> {
  await setDoc(keyDoc(uid), blob)
}

export async function clearSyncedKey(uid: string): Promise<void> {
  await deleteDoc(keyDoc(uid))
}
