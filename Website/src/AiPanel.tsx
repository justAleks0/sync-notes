import { useEffect, useRef, useState } from 'react'
import { ACTIONS, SYSTEM_PROMPT, buildCustomPrompt, type AiAction } from './ai/actions'
import { aiErrorMessage, streamCompletion, type StreamHandle } from './ai/providers'
import { isConfigured, loadAiSettings, AI_SETTINGS_CHANGED, type AiSettings } from './ai/settings'

/** Reads AI settings and stays in step when they change in the Settings modal. */
export function useAiSettings(): AiSettings {
  const [settings, setSettings] = useState<AiSettings>(loadAiSettings)

  useEffect(() => {
    const reload = () => setSettings(loadAiSettings())
    window.addEventListener(AI_SETTINGS_CHANGED, reload)
    // Also catches the key being changed in another tab.
    window.addEventListener('storage', reload)
    return () => {
      window.removeEventListener(AI_SETTINGS_CHANGED, reload)
      window.removeEventListener('storage', reload)
    }
  }, [])

  return settings
}

type Props = {
  settings: AiSettings
  title: string
  /** The selected text if there is a selection, otherwise the whole body. */
  source: string
  scope: 'selection' | 'note'
  onClose: () => void
  onReplace: (text: string) => void
  onAppend: (text: string) => void
}

export function AiPanel({
  settings,
  title,
  source,
  scope,
  onClose,
  onReplace,
  onAppend,
}: Props) {
  const [action, setAction] = useState<AiAction | null>(null)
  const [custom, setCustom] = useState('')
  const [output, setOutput] = useState('')
  const [running, setRunning] = useState(false)
  const [error, setError] = useState('')
  const stream = useRef<StreamHandle | null>(null)

  // Abandon an in-flight request if the panel closes — otherwise it keeps
  // streaming (and billing) into a component that no longer exists.
  useEffect(() => () => stream.current?.cancel(), [])

  async function run(prompt: string, chosen: AiAction | null) {
    if (!isConfigured(settings)) return
    setAction(chosen)
    setOutput('')
    setError('')
    setRunning(true)

    const { done, handle } = streamCompletion(
      settings.provider,
      settings.apiKey,
      settings.model,
      SYSTEM_PROMPT,
      prompt,
      (chunk) => setOutput((current) => current + chunk),
    )
    stream.current = handle

    try {
      await done
    } catch (err) {
      setError(aiErrorMessage(err))
    } finally {
      setRunning(false)
      stream.current = null
    }
  }

  const stop = () => {
    stream.current?.cancel()
    setRunning(false)
  }

  const canApply = output.trim().length > 0 && !running

  return (
    <div className="ai-panel">
      <header className="ai-head">
        <strong>Assist</strong>
        <span className="muted">
          {scope === 'selection' ? 'on your selection' : 'on this note'} · {settings.model}
        </span>
        <button className="link" onClick={onClose}>Close</button>
      </header>

      <div className="ai-actions">
        {ACTIONS.map((item) => (
          <button
            key={item.id}
            className={action?.id === item.id ? 'active' : ''}
            title={item.hint}
            disabled={running}
            onClick={() => run(item.build(source, title), item)}
          >
            {item.label}
          </button>
        ))}
      </div>

      <form
        className="row ai-custom"
        onSubmit={(e) => {
          e.preventDefault()
          if (custom.trim()) run(buildCustomPrompt(custom, source), null)
        }}
      >
        <input
          placeholder="Or ask for something specific…"
          value={custom}
          onChange={(e) => setCustom(e.target.value)}
          disabled={running}
        />
        <button type="submit" disabled={running || !custom.trim()}>Ask</button>
      </form>

      {error && <p className="error">{error}</p>}

      {(output || running) && (
        <div className="ai-output">
          <pre>{output}{running && <span className="ai-caret">▍</span>}</pre>
        </div>
      )}

      <footer className="ai-foot">
        {running ? (
          <button className="link danger" onClick={stop}>Stop</button>
        ) : (
          <>
            {/* Nothing is ever written to the note automatically — the result is
                a suggestion until the user picks where it goes. */}
            <button
              className="primary"
              disabled={!canApply}
              onClick={() => { onReplace(output.trim()); onClose() }}
            >
              {scope === 'selection' ? 'Replace selection' : 'Replace note'}
            </button>
            <button
              disabled={!canApply}
              onClick={() => { onAppend(output.trim()); onClose() }}
            >
              Insert below
            </button>
            <button
              disabled={!canApply}
              onClick={() => navigator.clipboard.writeText(output.trim())}
            >
              Copy
            </button>
          </>
        )}
      </footer>
    </div>
  )
}
