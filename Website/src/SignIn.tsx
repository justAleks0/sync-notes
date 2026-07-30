import { useState, type FormEvent } from 'react'
import {
  authErrorMessage,
  registerWithEmail,
  signInWithEmail,
  signInWithGoogle,
} from './useAuth'

export function SignIn() {
  const [mode, setMode] = useState<'signin' | 'register'>('signin')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function run(fn: () => Promise<unknown>) {
    setBusy(true)
    setError('')
    try {
      await fn()
    } catch (err) {
      setError(authErrorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    run(() => (mode === 'signin' ? signInWithEmail : registerWithEmail)(email, password))
  }

  return (
    <div className="signin-page">
      <form className="signin-card" onSubmit={onSubmit}>
        <h1>Sync Notes</h1>
        <p className="muted">Your notes, on every device.</p>

        <input
          type="email"
          placeholder="Email"
          autoComplete="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <input
          type="password"
          placeholder="Password"
          autoComplete={mode === 'signin' ? 'current-password' : 'new-password'}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />

        {error && <p className="error">{error}</p>}

        <button type="submit" className="primary" disabled={busy}>
          {mode === 'signin' ? 'Sign in' : 'Create account'}
        </button>

        <div className="divider"><span>or</span></div>

        <button type="button" onClick={() => run(signInWithGoogle)} disabled={busy}>
          Continue with Google
        </button>

        <p className="muted switch">
          {mode === 'signin' ? "Don't have an account?" : 'Already have an account?'}{' '}
          <button
            type="button"
            className="link"
            onClick={() => {
              setMode(mode === 'signin' ? 'register' : 'signin')
              setError('')
            }}
          >
            {mode === 'signin' ? 'Create one' : 'Sign in'}
          </button>
        </p>
      </form>
    </div>
  )
}
