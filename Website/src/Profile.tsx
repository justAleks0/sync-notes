import { useState, type FormEvent } from 'react'
import type { User } from 'firebase/auth'
import { AiSettingsCard } from './AiSettings'
import {
  GOOGLE_PROVIDER,
  PASSWORD_PROVIDER,
  authErrorMessage,
  changePassword,
  linkGoogle,
  linkPassword,
  providerIds,
  reauthWithGoogle,
  reauthWithPassword,
  signOutUser,
  unlinkProvider,
  updateDisplayName,
} from './useAuth'

type Props = {
  user: User
  onRefresh: () => Promise<void>
  onClose: () => void
}

export function Profile({ user, onRefresh, onClose }: Props) {
  const providers = providerIds(user)
  const hasPassword = providers.includes(PASSWORD_PROVIDER)
  const hasGoogle = providers.includes(GOOGLE_PROVIDER)
  // Firebase won't let you remove the only way back into an account.
  const canUnlink = providers.length > 1

  const [name, setName] = useState(user.displayName ?? '')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  // Firebase demands a fresh sign-in for sensitive changes. When it does, we park the
  // action here and replay it once the user has proved who they are.
  const [pending, setPending] = useState<(() => Promise<void>) | null>(null)
  const [reauthPassword, setReauthPassword] = useState('')

  async function run(action: () => Promise<unknown>, successMessage: string) {
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await action()
      await onRefresh()
      setNotice(successMessage)
    } catch (err) {
      if ((err as { code?: string })?.code === 'auth/requires-recent-login') {
        setPending(() => async () => {
          await action()
          await onRefresh()
          setNotice(successMessage)
        })
      }
      setError(authErrorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  async function confirmIdentity(method: () => Promise<unknown>) {
    setBusy(true)
    setError('')
    try {
      await method()
      const replay = pending
      setPending(null)
      setReauthPassword('')
      if (replay) await replay()
    } catch (err) {
      setError(authErrorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const initial = (user.displayName || user.email || '?').trim().charAt(0).toUpperCase()

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <header className="modal-head">
          <h2>Settings</h2>
          <button className="link" onClick={onClose}>Close</button>
        </header>

        <div className="modal-body">
          <div className="identity">
            <div className="avatar">{initial}</div>
            <div>
              <div className="identity-name">{user.displayName || 'No name set'}</div>
              <div className="muted">{user.email}</div>
            </div>
          </div>

          {error && <p className="error">{error}</p>}
          {notice && <p className="notice">{notice}</p>}

          {pending && (
            <section className="card reauth">
              <h3>Confirm it's you</h3>
              <p className="muted">
                You signed in a while ago. Confirm your identity to finish that change.
              </p>
              {hasPassword && (
                <form
                  className="row"
                  onSubmit={(e: FormEvent) => {
                    e.preventDefault()
                    confirmIdentity(() => reauthWithPassword(reauthPassword))
                  }}
                >
                  <input
                    type="password"
                    placeholder="Current password"
                    autoComplete="current-password"
                    value={reauthPassword}
                    onChange={(e) => setReauthPassword(e.target.value)}
                  />
                  <button type="submit" className="primary" disabled={busy}>Confirm</button>
                </form>
              )}
              {hasGoogle && (
                <button onClick={() => confirmIdentity(reauthWithGoogle)} disabled={busy}>
                  Confirm with Google
                </button>
              )}
              <button
                className="link"
                onClick={() => {
                  setPending(null)
                  setError('')
                }}
              >
                Cancel
              </button>
            </section>
          )}

          <section className="card">
            <h3>Username</h3>
            <p className="muted">The name shown on your notes. Only you see it for now.</p>
            <form
              className="row"
              onSubmit={(e: FormEvent) => {
                e.preventDefault()
                run(() => updateDisplayName(name), 'Username saved.')
              }}
            >
              <input
                placeholder="Your name"
                value={name}
                maxLength={60}
                onChange={(e) => setName(e.target.value)}
              />
              <button
                type="submit"
                className="primary"
                disabled={busy || name.trim() === (user.displayName ?? '').trim()}
              >
                Save
              </button>
            </form>
          </section>

          <section className="card">
            <h3>Sign-in methods</h3>
            <p className="muted">
              Connect both and you can sign in either way — same account, same notes.
            </p>

            <div className="provider">
              <div>
                <strong>Email &amp; password</strong>
                <div className="muted">{hasPassword ? user.email : 'Not set up'}</div>
              </div>
              {hasPassword && canUnlink && (
                <button
                  className="link danger"
                  disabled={busy}
                  onClick={() => run(() => unlinkProvider(PASSWORD_PROVIDER), 'Password sign-in removed.')}
                >
                  Disconnect
                </button>
              )}
            </div>

            <form
              className="row"
              onSubmit={(e: FormEvent) => {
                e.preventDefault()
                const action = hasPassword
                  ? () => changePassword(password)
                  : () => linkPassword(password)
                run(action, hasPassword ? 'Password changed.' : 'Password sign-in added.')
                setPassword('')
              }}
            >
              <input
                type="password"
                placeholder={hasPassword ? 'New password' : 'Choose a password'}
                autoComplete="new-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
              <button type="submit" disabled={busy || password.length < 6}>
                {hasPassword ? 'Change' : 'Add'}
              </button>
            </form>

            <div className="provider">
              <div>
                <strong>Google</strong>
                <div className="muted">
                  {hasGoogle
                    ? user.providerData.find((p) => p.providerId === GOOGLE_PROVIDER)?.email
                    : 'Not connected'}
                </div>
              </div>
              {hasGoogle ? (
                canUnlink && (
                  <button
                    className="link danger"
                    disabled={busy}
                    onClick={() => run(() => unlinkProvider(GOOGLE_PROVIDER), 'Google disconnected.')}
                  >
                    Disconnect
                  </button>
                )
              ) : (
                <button
                  disabled={busy}
                  onClick={() => run(linkGoogle, 'Google connected.')}
                >
                  Connect
                </button>
              )}
            </div>

            {!canUnlink && (
              <p className="muted hint">
                Add a second method before removing the first — otherwise you'd lock
                yourself out.
              </p>
            )}
          </section>

          <AiSettingsCard uid={user.uid} />

          <button className="link danger signout" onClick={signOutUser}>Sign out</button>
        </div>
      </div>
    </div>
  )
}
