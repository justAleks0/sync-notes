import { useCallback, useEffect, useReducer, useState } from 'react'
import {
  EmailAuthProvider,
  GoogleAuthProvider,
  createUserWithEmailAndPassword,
  linkWithCredential,
  linkWithPopup,
  onAuthStateChanged,
  reauthenticateWithCredential,
  reauthenticateWithPopup,
  signInWithEmailAndPassword,
  signInWithPopup,
  signOut,
  unlink,
  updatePassword,
  updateProfile,
  type User,
} from 'firebase/auth'
import { auth } from './firebase'

export const PASSWORD_PROVIDER = 'password'
export const GOOGLE_PROVIDER = 'google.com'

export function useAuth() {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(true)
  // Profile edits mutate the User object in place rather than emitting a new one, so
  // onAuthStateChanged never fires for them. This counter forces the re-render.
  const [, bump] = useReducer((n: number) => n + 1, 0)

  useEffect(() => onAuthStateChanged(auth, (u) => {
    setUser(u)
    setLoading(false)
  }), [])

  const refresh = useCallback(async () => {
    await auth.currentUser?.reload()
    bump()
  }, [])

  return { user, loading, refresh }
}

/** Which sign-in methods this account currently has, e.g. ['password', 'google.com']. */
export const providerIds = (user: User): string[] =>
  user.providerData.map((p) => p.providerId)

const current = (): User => {
  const user = auth.currentUser
  if (!user) throw new Error('Not signed in.')
  return user
}

export const linkGoogle = () =>
  retryWhenVisible(() => linkWithPopup(current(), new GoogleAuthProvider()))

/**
 * Adds a password to an account that only has Google. Firebase needs an email
 * alongside the password, and it must be the one already on the account — this is
 * "set a password for your existing account", not "add a second identity".
 */
export const linkPassword = (password: string) =>
  retryWhenVisible(() => {
    const user = current()
    if (!user.email) throw new Error('This account has no email address to attach a password to.')
    return linkWithCredential(user, EmailAuthProvider.credential(user.email, password))
  })

export const unlinkProvider = (providerId: string) => unlink(current(), providerId)

export const updateDisplayName = (displayName: string) =>
  updateProfile(current(), { displayName: displayName.trim() || null })

export const changePassword = (password: string) => updatePassword(current(), password)

/**
 * Firebase rejects sensitive changes (unlinking, password changes) when the sign-in
 * is more than a few minutes old. Proving identity again clears that.
 */
export const reauthWithPassword = (password: string) => {
  const user = current()
  if (!user.email) throw new Error('This account has no email address.')
  return reauthenticateWithCredential(user, EmailAuthProvider.credential(user.email, password))
}

export const reauthWithGoogle = () =>
  retryWhenVisible(() => reauthenticateWithPopup(current(), new GoogleAuthProvider()))

/**
 * Firebase Auth keeps its session in IndexedDB, and parks that database whenever the
 * page is hidden — a backgrounded tab, a minimised window, switching apps on a phone.
 * Any auth call made in that window rejects with a raw internal "Database is
 * closing/hidden". It clears itself the moment the page is visible again, so the fix
 * is to wait it out and retry rather than show the user Firebase's plumbing.
 */
function isDatabaseHiddenError(err: unknown): boolean {
  return (err as Error)?.message?.includes('Database is closing/hidden') ?? false
}

function pageVisible(): Promise<void> {
  if (document.visibilityState === 'visible') return Promise.resolve()
  return new Promise((resolve) => {
    const onChange = () => {
      if (document.visibilityState !== 'visible') return
      document.removeEventListener('visibilitychange', onChange)
      resolve()
    }
    document.addEventListener('visibilitychange', onChange)
  })
}

async function retryWhenVisible<T>(op: () => Promise<T>): Promise<T> {
  try {
    return await op()
  } catch (err) {
    if (!isDatabaseHiddenError(err)) throw err
    await pageVisible()
    return op()
  }
}

export const signInWithGoogle = () =>
  retryWhenVisible(() => signInWithPopup(auth, new GoogleAuthProvider()))
export const signInWithEmail = (email: string, password: string) =>
  retryWhenVisible(() => signInWithEmailAndPassword(auth, email, password))
export const registerWithEmail = (email: string, password: string) =>
  retryWhenVisible(() => createUserWithEmailAndPassword(auth, email, password))
export const signOutUser = () => signOut(auth)

/** Turns Firebase's auth/... error codes into something a human can read. */
export function authErrorMessage(err: unknown): string {
  const code = (err as { code?: string })?.code ?? ''
  switch (code) {
    case 'auth/invalid-email':
      return "That doesn't look like a valid email address."
    case 'auth/missing-password':
      return 'Enter your password.'
    case 'auth/weak-password':
      return 'Password must be at least 6 characters.'
    case 'auth/email-already-in-use':
      return 'That email already has an account — try signing in instead.'
    case 'auth/invalid-credential':
    case 'auth/wrong-password':
    case 'auth/user-not-found':
      return 'Wrong email or password.'
    case 'auth/too-many-requests':
      return 'Too many attempts. Wait a minute and try again.'
    case 'auth/provider-already-linked':
      return 'That sign-in method is already connected.'
    case 'auth/credential-already-in-use':
      return 'That Google account is already attached to a different Sync Notes account.'
    case 'auth/requires-recent-login':
      return 'For security, confirm who you are before changing this.'
    case 'auth/no-such-provider':
      return "That sign-in method isn't connected."
    case 'auth/popup-closed-by-user':
    case 'auth/cancelled-popup-request':
      return ''
    case 'auth/network-request-failed':
      return 'No connection. Check your network and try again.'
    default:
      // Never show Firebase's internal storage errors — retryWhenVisible handles the
      // common case, this catches whatever slips past it.
      if (isDatabaseHiddenError(err)) return 'Lost the connection to local storage. Try again.'
      return (err as Error)?.message ?? 'Something went wrong.'
  }
}
