import { useEffect, useMemo, useRef, useState } from 'react'
import {
  ACTIONS,
  EDIT_SYSTEM_PROMPT,
  SYSTEM_PROMPT,
  buildCustomPrompt,
  type AiAction,
} from './ai/actions'
import { applyEdits, parseEdits, statusOf, type SuggestedEdit } from './ai/edits'
import { aiErrorMessage, streamCompletion, type StreamHandle } from './ai/providers'
import { supportsVision } from './ai/models'
import { extractImages, MAX_IMAGES } from './ai/vision'
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

/**
 * The suggestions themselves, each with the exact before and after so the change
 * can be judged without hunting for it in the note.
 */
function EditList({
  edits,
  source,
  chosen,
  onToggle,
}: {
  edits: SuggestedEdit[]
  source: string
  chosen: Set<string>
  onToggle: (id: string) => void
}) {
  if (edits.length === 0) {
    return <p className="muted">Nothing to change — it reads fine as it is.</p>
  }

  return (
    <ul className="ai-edits">
      {edits.map((edit) => {
        const status = statusOf(source, edit)
        const missing = status === 'missing'

        return (
          <li key={edit.id} className={missing ? 'stale' : ''}>
            <label>
              <input
                type="checkbox"
                checked={chosen.has(edit.id)}
                disabled={missing}
                onChange={() => onToggle(edit.id)}
              />
              <span className="ai-edit-body">
                <span className="ai-edit-why">{edit.why}</span>
                <span className="ai-edit-diff">
                  <del>{edit.find}</del>
                  {edit.replace && <ins>{edit.replace}</ins>}
                </span>
                {missing && (
                  <span className="muted">
                    That text isn't in the note any more — skipping this one.
                  </span>
                )}
                {status === 'ambiguous' && (
                  <span className="muted">Appears more than once; the first is changed.</span>
                )}
              </span>
            </label>
          </li>
        )
      })}
    </ul>
  )
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
  const [withImages, setWithImages] = useState(true)
  // Set only for the "Suggest edits" action, which comes back as a list to tick
  // off rather than as prose. Null means "not that kind of result".
  const [edits, setEdits] = useState<SuggestedEdit[] | null>(null)
  const [chosen, setChosen] = useState<Set<string>>(new Set())
  const stream = useRef<StreamHandle | null>(null)

  const images = useMemo(() => extractImages(source), [source])
  const canSee = supportsVision(settings.provider, settings.model)
  // Slicing here rather than in the extractor so the panel can say how many were
  // left behind.
  const attached = canSee && withImages ? images.slice(0, MAX_IMAGES) : []

  // Abandon an in-flight request if the panel closes — otherwise it keeps
  // streaming (and billing) into a component that no longer exists.
  useEffect(() => () => stream.current?.cancel(), [])

  async function run(prompt: string, picked: AiAction | null) {
    if (!isConfigured(settings)) return
    setAction(picked)
    setOutput('')
    setError('')
    setEdits(null)
    setChosen(new Set())
    setRunning(true)

    let collected = ''
    const { done, handle } = streamCompletion(
      settings.provider,
      settings.apiKey,
      settings.model,
      picked?.result === 'edits' ? EDIT_SYSTEM_PROMPT : SYSTEM_PROMPT,
      prompt,
      attached,
      (chunk) => {
        collected += chunk
        setOutput(collected)
      },
    )
    stream.current = handle

    try {
      await done
      if (picked?.result === 'edits') {
        const parsed = parseEdits(collected)
        if (parsed === null) {
          // Fall back to showing the raw reply rather than claiming failure —
          // the text is usually still readable and useful.
          setError("Couldn't read that as a list of edits. The raw reply is below.")
        } else {
          setEdits(parsed)
          // Everything that can still be applied starts ticked: the common case
          // is wanting most of them.
          setChosen(
            new Set(parsed.filter((e) => statusOf(source, e) !== 'missing').map((e) => e.id)),
          )
        }
      }
    } catch (err) {
      setError(aiErrorMessage(err))
    } finally {
      setRunning(false)
      stream.current = null
    }
  }

  function applyChosen() {
    if (!edits) return
    const picked = edits.filter((e) => chosen.has(e.id))
    if (picked.length === 0) return

    const { text } = applyEdits(source, picked)
    onReplace(text)
    onClose()
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

      {images.length > 0 && (
        <div className="ai-images">
          {canSee ? (
            <label>
              <input
                type="checkbox"
                checked={withImages}
                disabled={running}
                onChange={(e) => setWithImages(e.target.checked)}
              />
              <span>
                Show {images.length === 1 ? 'the image' : `the ${images.length} images`} to the
                model
                {images.length > MAX_IMAGES && (
                  <span className="muted"> — the first {MAX_IMAGES} of them</span>
                )}
              </span>
            </label>
          ) : (
            <span className="muted">
              {settings.model} can't read images. Pick a model that can in Settings to have the{' '}
              {images.length === 1 ? 'image' : `${images.length} images`} taken into account.
            </span>
          )}
        </div>
      )}

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

      {edits !== null ? (
        <EditList
          edits={edits}
          source={source}
          chosen={chosen}
          onToggle={(id) =>
            setChosen((current) => {
              const next = new Set(current)
              if (next.has(id)) next.delete(id)
              else next.add(id)
              return next
            })
          }
        />
      ) : (
        (output || running) && (
          <div className="ai-output">
            <pre>{output}{running && <span className="ai-caret">▍</span>}</pre>
          </div>
        )
      )}

      <footer className="ai-foot">
        {running ? (
          <button className="link danger" onClick={stop}>Stop</button>
        ) : edits !== null ? (
          <>
            {/* Same rule as everywhere else here: nothing reaches the note until
                the user says which parts of it should. */}
            <button className="primary" disabled={chosen.size === 0} onClick={applyChosen}>
              {chosen.size === 0
                ? 'Nothing selected'
                : `Apply ${chosen.size} ${chosen.size === 1 ? 'edit' : 'edits'}`}
            </button>
            <button onClick={onClose}>Cancel</button>
          </>
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
