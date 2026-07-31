import { useState } from 'react'

/**
 * Shown before key sync is ever switched on, never after.
 *
 * It says what the feature does, what the user has to do, and what can go wrong,
 * in that order and in plain words. A feature that moves a billable credential
 * into a database should not be a switch someone flips without reading anything —
 * and the honest downsides are the reason to show this, not the polite framing of
 * the upside.
 */
export function KeySyncDialog({
  busy,
  error,
  onConfirm,
  onCancel,
}: {
  busy: boolean
  error: string
  /** Given the chosen passphrase. */
  onConfirm: (passphrase: string) => void
  onCancel: () => void
}) {
  const [passphrase, setPassphrase] = useState('')
  const [again, setAgain] = useState('')
  const [visible, setVisible] = useState(false)

  const tooShort = passphrase.length > 0 && passphrase.length < 10
  const mismatch = again.length > 0 && again !== passphrase
  const ready = passphrase.length >= 10 && again === passphrase && !busy

  return (
    <div className="modal-backdrop" onClick={busy ? undefined : onCancel}>
      <div className="modal keysync" onClick={(e) => e.stopPropagation()}>
        <h2>Sync your API key to your other devices?</h2>

        <section>
          <h3>What this does</h3>
          <p>
            Your key is encrypted on this device with a passphrase you choose, and only
            the encrypted result is stored in your account. Any device you sign in on can
            unlock it with that passphrase, instead of you pasting the key in again.
          </p>
        </section>

        <section>
          <h3>What you'll need to do</h3>
          <ul>
            <li>Choose a passphrase now. It is not your account password, and it never leaves this device.</li>
            <li>Enter it once on each other device, to unlock the key there.</li>
            <li>
              Sync Notes, Firebase and Google only ever hold the encrypted blob — none of
              them can read your key.
            </li>
          </ul>
        </section>

        <section>
          <h3>The risks, plainly</h3>
          <ul className="risks">
            <li>
              <strong>Forget the passphrase and the synced key is gone.</strong> Nobody can
              reset it — not us, not Google. You'd paste the key in again and start over.
            </li>
            <li>
              <strong>It widens what a stolen account is worth.</strong> Today someone who
              got into your account gets your notes. With this on, someone who gets into
              your account <em>and</em> knows your passphrase gets a working, billable key.
            </li>
            <li>
              <strong>A weak passphrase makes this weak.</strong> The encryption is only as
              good as what you pick — a guessable phrase can be attacked offline by anyone
              holding the encrypted copy.
            </li>
            <li>
              It does not change what happens on this device: the key still sits unencrypted
              in this browser's storage, exactly as it does now.
            </li>
          </ul>
        </section>

        <label>
          Passphrase
          <input
            type={visible ? 'text' : 'password'}
            value={passphrase}
            onChange={(e) => setPassphrase(e.target.value)}
            placeholder="At least 10 characters"
            autoComplete="new-password"
          />
        </label>
        <label>
          Passphrase again
          <input
            type={visible ? 'text' : 'password'}
            value={again}
            onChange={(e) => setAgain(e.target.value)}
            autoComplete="new-password"
          />
        </label>
        <label className="row-check">
          <input type="checkbox" checked={visible} onChange={(e) => setVisible(e.target.checked)} />
          Show what I'm typing
        </label>

        {tooShort && <p className="muted">Ten characters or more, please — this is the whole lock.</p>}
        {mismatch && <p className="error">Those two don't match.</p>}
        {error && <p className="error">{error}</p>}

        <footer className="modal-foot">
          <button className="primary" disabled={!ready} onClick={() => onConfirm(passphrase)}>
            {busy ? 'Encrypting…' : 'Turn it on'}
          </button>
          <button disabled={busy} onClick={onCancel}>Keep it off</button>
        </footer>
      </div>
    </div>
  )
}

/** Asks for the passphrase on a device that has an encrypted key but no plain one. */
export function KeyUnlockDialog({
  busy,
  error,
  onUnlock,
  onCancel,
}: {
  busy: boolean
  error: string
  onUnlock: (passphrase: string) => void
  onCancel: () => void
}) {
  const [passphrase, setPassphrase] = useState('')

  return (
    <div className="modal-backdrop" onClick={busy ? undefined : onCancel}>
      <div className="modal keysync" onClick={(e) => e.stopPropagation()}>
        <h2>Unlock your synced API key</h2>
        <p className="muted">
          This account has an encrypted key stored. Enter the passphrase you chose when you
          turned syncing on, and it will be decrypted here.
        </p>

        <form
          onSubmit={(e) => {
            e.preventDefault()
            if (passphrase) onUnlock(passphrase)
          }}
        >
          <label>
            Passphrase
            <input
              type="password"
              value={passphrase}
              onChange={(e) => setPassphrase(e.target.value)}
              autoComplete="current-password"
              autoFocus
            />
          </label>
          {error && <p className="error">{error}</p>}
          <footer className="modal-foot">
            <button className="primary" type="submit" disabled={!passphrase || busy}>
              {busy ? 'Unlocking…' : 'Unlock'}
            </button>
            <button type="button" disabled={busy} onClick={onCancel}>Not now</button>
          </footer>
        </form>
      </div>
    </div>
  )
}
