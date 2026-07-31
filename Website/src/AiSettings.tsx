import { useEffect, useState } from 'react'
import { PROVIDERS, aiErrorMessage, listModels } from './ai/providers'
import { RECOMMENDED, defaultModel, findRecommendation, monthlyEstimate } from './ai/models'
import {
  DEFAULT_SETTINGS,
  clearAiSettings,
  loadAiSettings,
  saveAiSettings,
  type ProviderId,
} from './ai/settings'
import { KeySyncDialog, KeyUnlockDialog } from './KeySyncDialog'
import {
  clearSyncedKey,
  decryptKey,
  encryptKey,
  loadSyncedKey,
  saveSyncedKey,
} from './ai/keySync'

export function AiSettingsCard({ uid }: { uid: string }) {
  const [settings, setSettings] = useState(loadAiSettings)
  const [models, setModels] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  // Whether an encrypted copy exists in the account. Whether *this* device can
  // read it is a separate question, answered by settings.apiKey — conflating the
  // two is what made the unlock prompt unreachable on a device that had never
  // seen the key, which is the only device that needs it.
  const [synced, setSynced] = useState(false)
  const [showDialog, setShowDialog] = useState(false)
  const [showUnlock, setShowUnlock] = useState(false)
  const [syncBusy, setSyncBusy] = useState(false)
  const [syncError, setSyncError] = useState('')

  useEffect(() => {
    let live = true
    loadSyncedKey(uid)
      .then((blob) => { if (live) setSynced(blob !== null) })
      .catch(() => {})
    return () => { live = false }
  }, [uid])

  async function startSyncing(passphrase: string) {
    setSyncBusy(true)
    setSyncError('')
    try {
      const blob = await encryptKey(
        settings.apiKey.trim(),
        passphrase,
        settings.provider,
        settings.model,
      )
      await saveSyncedKey(uid, blob)
      setSynced(true)
      setShowDialog(false)
      setNotice('Encrypted copy saved. Your other devices can unlock it with that passphrase.')
    } catch (err) {
      setSyncError((err as Error)?.message ?? 'Could not encrypt the key.')
    } finally {
      setSyncBusy(false)
    }
  }

  async function stopSyncing() {
    setSyncBusy(true)
    setSyncError('')
    try {
      await clearSyncedKey(uid)
      setSynced(false)
      setNotice('Encrypted copy deleted from your account.')
    } catch (err) {
      setSyncError((err as Error)?.message ?? 'Could not remove the stored copy.')
    } finally {
      setSyncBusy(false)
    }
  }

  async function unlock(passphrase: string) {
    setSyncBusy(true)
    setSyncError('')
    try {
      const blob = await loadSyncedKey(uid)
      if (!blob) {
        setSyncError('There is no encrypted key stored any more.')
        return
      }
      const apiKey = await decryptKey(blob, passphrase)
      if (apiKey === null) {
        setSyncError("That passphrase didn't work.")
        return
      }
      setSettings((s) => ({
        ...s,
        apiKey,
        provider: blob.provider,
        model: blob.model || s.model,
        enabled: true,
      }))
      setSynced(true)
      setShowUnlock(false)
      setNotice('Key unlocked and saved on this device.')
    } catch (err) {
      setSyncError((err as Error)?.message ?? 'Could not read the stored key.')
    } finally {
      setSyncBusy(false)
    }
  }

  const provider = PROVIDERS.find((p) => p.id === settings.provider)!
  const recommended = RECOMMENDED[settings.provider]
  const chosen = findRecommendation(settings.provider, settings.model)

  // Persist on every change so the editor picks it up immediately.
  useEffect(() => { saveAiSettings(settings) }, [settings])

  async function connect() {
    setBusy(true)
    setError('')
    setNotice('')
    try {
      const available = await listModels(settings.provider, settings.apiKey.trim())
      setModels(available)
      // Only auto-pick when the saved model isn't valid for this key.
      const model = available.includes(settings.model)
        ? settings.model
        : defaultModel(settings.provider, available)
      setSettings((s) => ({ ...s, model }))
      setNotice(`Connected — ${available.length} usable chat models.`)
    } catch (err) {
      setModels([])
      setError(aiErrorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  function forget() {
    clearAiSettings()
    setSettings({ ...DEFAULT_SETTINGS })
    setModels([])
    setNotice('Key removed from this device.')
  }

  return (
    <section className="card">
      <h3>AI assistance</h3>
      <p className="muted">
        Off by default. Bring your own API key and the editor can rewrite, summarise,
        continue, and suggest edits on a note.
      </p>

      <label className="toggle">
        <input
          type="checkbox"
          checked={settings.enabled}
          onChange={(e) => setSettings((s) => ({ ...s, enabled: e.target.checked }))}
        />
        <span>Enable AI assistance</span>
      </label>

      {settings.enabled && (
        <>
          <div className="row">
            <select
              value={settings.provider}
              onChange={(e) => {
                const next = e.target.value as ProviderId
                // A key and model from one vendor mean nothing to the other.
                setSettings((s) => ({ ...s, provider: next, apiKey: '', model: '' }))
                setModels([])
                setNotice('')
                setError('')
              }}
            >
              {PROVIDERS.map((p) => (
                <option key={p.id} value={p.id}>{p.label}</option>
              ))}
            </select>
          </div>

          <div className="row">
            <input
              type="password"
              placeholder={provider.keyPlaceholder}
              autoComplete="off"
              spellCheck={false}
              value={settings.apiKey}
              onChange={(e) => setSettings((s) => ({ ...s, apiKey: e.target.value }))}
            />
            <button onClick={connect} disabled={busy || !settings.apiKey.trim()}>
              {busy ? 'Checking…' : 'Connect'}
            </button>
          </div>

          {models.length > 0 && (
            <>
              <div className="row">
                <select
                  value={settings.model}
                  onChange={(e) => setSettings((s) => ({ ...s, model: e.target.value }))}
                >
                  {/* Suited-to-this-app first; everything else still reachable. */}
                  <optgroup label="Suggested for notes">
                    {recommended
                      .filter((r) => models.includes(r.id))
                      .map((r) => (
                        <option key={r.id} value={r.id}>{r.label}</option>
                      ))}
                  </optgroup>
                  <optgroup label="All chat models">
                    {models
                      .filter((id) => !recommended.some((r) => r.id === id))
                      .map((id) => <option key={id} value={id}>{id}</option>)}
                  </optgroup>
                </select>
              </div>

              {chosen && (
                <p className="muted hint">
                  {chosen.note}{' '}
                  <strong>
                    ${chosen.inputPerM}/${chosen.outputPerM} per 1M tokens — {monthlyEstimate(chosen)}
                  </strong>{' '}
                  at typical use.
                </p>
              )}
            </>
          )}

          {settings.model && models.length === 0 && (
            <p className="muted hint">Using saved model <code>{settings.model}</code>.</p>
          )}

          {error && <p className="error">{error}</p>}
          {notice && <p className="notice">{notice}</p>}

          <p className="muted hint">
            By default your key is stored on this device only — never in the notes
            database — so you enter it once per device. Requests go straight from here to{' '}
            {provider.label}; nothing passes through Sync Notes. Note content you run an
            action on is sent to them, and they bill your account for it.{' '}
            <a href={provider.consoleUrl} target="_blank" rel="noreferrer noopener">
              Get a key
            </a>
          </p>

          <label className="toggle">
            <input
              type="checkbox"
              checked={synced}
              disabled={syncBusy || (!synced && !settings.apiKey.trim())}
              onChange={(e) => (e.target.checked ? setShowDialog(true) : stopSyncing())}
            />
            <span>
              Sync this key to my other devices, encrypted
              {!settings.apiKey.trim() && !synced && ' — add a key first'}
            </span>
          </label>

          {synced && (
            <p className="muted hint">
              An encrypted copy is in your account. Turning this off deletes it; the key
              stays on this device.
            </p>
          )}

          {/* The device this matters on is the one with an envelope in the account
              and nothing local to open it with. */}
          {synced && !settings.apiKey.trim() && (
            <p className="muted hint">
              This account has an encrypted key stored.{' '}
              <button className="link" onClick={() => setShowUnlock(true)}>Unlock it here</button>
            </p>
          )}

          {syncError && <p className="error">{syncError}</p>}

          {settings.apiKey && (
            <button className="link danger" onClick={forget}>Forget key on this device</button>
          )}
        </>
      )}

      {showDialog && (
        <KeySyncDialog
          busy={syncBusy}
          error={syncError}
          onConfirm={startSyncing}
          onCancel={() => { setShowDialog(false); setSyncError('') }}
        />
      )}

      {showUnlock && (
        <KeyUnlockDialog
          busy={syncBusy}
          error={syncError}
          onUnlock={unlock}
          onCancel={() => { setShowUnlock(false); setSyncError('') }}
        />
      )}
    </section>
  )
}
