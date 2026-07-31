export type ProviderId = 'anthropic' | 'openai'

export type AiSettings = {
  /** Off until the user opts in. Nothing is sent anywhere while this is false. */
  enabled: boolean
  provider: ProviderId
  apiKey: string
  model: string
}

export const DEFAULT_SETTINGS: AiSettings = {
  enabled: false,
  provider: 'anthropic',
  apiKey: '',
  model: '',
}

/**
 * Stored in localStorage, per device — deliberately NOT in Firestore.
 *
 * An API key is a billable credential. Putting it in the synced database would
 * write it to a server, replicate it to every device, and leave it in the
 * offline cache on each one; anyone who reached the account would get a working
 * key rather than just some notes. Keeping it local means the key only ever
 * travels from this browser to the vendor that issued it.
 *
 * The tradeoff is real and worth stating: you enter the key once per device.
 */
const STORAGE_KEY = 'sync-notes:ai'

export function loadAiSettings(): AiSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { ...DEFAULT_SETTINGS }
    const parsed = JSON.parse(raw) as Partial<AiSettings>
    return {
      enabled: parsed.enabled === true,
      provider: parsed.provider === 'openai' ? 'openai' : 'anthropic',
      // Trimmed on the way in: a key is almost always pasted, and a trailing
      // newline makes an invalid HTTP header, which fetch rejects as a bare
      // network failure rather than anything that mentions the key.
      apiKey: typeof parsed.apiKey === 'string' ? parsed.apiKey.trim() : '',
      model: typeof parsed.model === 'string' ? parsed.model : '',
    }
  } catch {
    // Corrupt or unavailable storage shouldn't take the editor down with it.
    return { ...DEFAULT_SETTINGS }
  }
}

export function saveAiSettings(settings: AiSettings): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...settings, apiKey: settings.apiKey.trim() }))
  window.dispatchEvent(new Event(AI_SETTINGS_CHANGED))
}

export function clearAiSettings(): void {
  localStorage.removeItem(STORAGE_KEY)
  window.dispatchEvent(new Event(AI_SETTINGS_CHANGED))
}

/** Lets the editor react the moment settings change in the modal. */
export const AI_SETTINGS_CHANGED = 'sync-notes:ai-changed'

export const isConfigured = (s: AiSettings): boolean =>
  s.enabled && s.apiKey.trim().length > 0 && s.model.trim().length > 0
