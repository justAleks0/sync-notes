import { useEffect, useState } from 'react'
import { PROVIDERS, aiErrorMessage, listModels, suggestModel } from './ai/providers'
import {
  DEFAULT_SETTINGS,
  clearAiSettings,
  loadAiSettings,
  saveAiSettings,
  type ProviderId,
} from './ai/settings'

export function AiSettingsCard() {
  const [settings, setSettings] = useState(loadAiSettings)
  const [models, setModels] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  const provider = PROVIDERS.find((p) => p.id === settings.provider)!

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
        : suggestModel(settings.provider, available)
      setSettings((s) => ({ ...s, model }))
      setNotice(`Connected — ${available.length} models available.`)
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
            <div className="row">
              <select
                value={settings.model}
                onChange={(e) => setSettings((s) => ({ ...s, model: e.target.value }))}
              >
                {models.map((id) => <option key={id} value={id}>{id}</option>)}
              </select>
            </div>
          )}

          {settings.model && models.length === 0 && (
            <p className="muted hint">Using saved model <code>{settings.model}</code>.</p>
          )}

          {error && <p className="error">{error}</p>}
          {notice && <p className="notice">{notice}</p>}

          <p className="muted hint">
            Your key is stored on this device only — never in the notes database, so it
            never syncs to your other devices and you'll enter it once per device.
            Requests go straight from here to {provider.label}; nothing passes through
            Sync Notes. Note content you run an action on is sent to them, and they bill
            your account for it.{' '}
            <a href={provider.consoleUrl} target="_blank" rel="noreferrer noopener">
              Get a key
            </a>
          </p>

          {settings.apiKey && (
            <button className="link danger" onClick={forget}>Forget key on this device</button>
          )}
        </>
      )}
    </section>
  )
}
