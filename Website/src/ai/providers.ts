import Anthropic from '@anthropic-ai/sdk'
import OpenAI from 'openai'
import type { ProviderId } from './settings'
import { isUsableChatModel } from './models'

export type ProviderInfo = {
  id: ProviderId
  label: string
  keyPlaceholder: string
  consoleUrl: string
}

export const PROVIDERS: ProviderInfo[] = [
  {
    id: 'anthropic',
    label: 'Claude (Anthropic)',
    keyPlaceholder: 'sk-ant-…',
    consoleUrl: 'https://console.anthropic.com/settings/keys',
  },
  {
    id: 'openai',
    label: 'OpenAI',
    keyPlaceholder: 'sk-…',
    consoleUrl: 'https://platform.openai.com/api-keys',
  },
]

/**
 * Both SDKs refuse to run in a browser unless told the exposure is intentional.
 * Here it is: the key belongs to the person typing it, it is read from their own
 * device's storage, and it goes only to the vendor that issued it. There is no
 * server in between to leak it to, which is the whole point of the design.
 */
const anthropicClient = (apiKey: string) =>
  new Anthropic({
    apiKey,
    dangerouslyAllowBrowser: true,
    // Anthropic blocks direct browser calls unless this opt-in header is present.
    defaultHeaders: { 'anthropic-dangerous-direct-browser-access': 'true' },
  })

const openaiClient = (apiKey: string) =>
  new OpenAI({ apiKey, dangerouslyAllowBrowser: true })

/**
 * Model IDs move faster than any list we could hard-code, so ask the provider
 * with the user's own key instead of shipping a guess that 404s next quarter.
 * The result is filtered to models that can actually answer a chat request —
 * unfiltered, an OpenAI account returns well over a hundred entries, most of
 * them audio, image, or embedding models that would simply error here.
 */
export async function listModels(provider: ProviderId, apiKey: string): Promise<string[]> {
  const ids =
    provider === 'anthropic'
      ? (await anthropicClient(apiKey).models.list({ limit: 100 })).data.map((m) => m.id)
      : (await openaiClient(apiKey).models.list()).data.map((m) => m.id)

  return ids.filter((id) => isUsableChatModel(provider, id)).sort()
}

export type StreamHandle = { cancel: () => void }

/**
 * Streams a completion, calling [onText] with each chunk.
 *
 * Streaming rather than awaiting the whole response: these requests can run for
 * a while on a long note, and a request that returns nothing until it is
 * finished both feels broken and risks an HTTP timeout.
 */
export function streamCompletion(
  provider: ProviderId,
  apiKey: string,
  model: string,
  system: string,
  prompt: string,
  onText: (chunk: string) => void,
): { done: Promise<void>; handle: StreamHandle } {
  const controller = new AbortController()
  const handle: StreamHandle = { cancel: () => controller.abort() }

  const done = (async () => {
    if (provider === 'anthropic') {
      const stream = anthropicClient(apiKey).messages.stream(
        {
          model,
          max_tokens: 16000,
          system,
          messages: [{ role: 'user', content: prompt }],
        },
        { signal: controller.signal },
      )
      stream.on('text', onText)
      await stream.finalMessage()
      return
    }

    const stream = await openaiClient(apiKey).chat.completions.create(
      {
        model,
        stream: true,
        messages: [
          { role: 'system', content: system },
          { role: 'user', content: prompt },
        ],
      },
      { signal: controller.signal },
    )
    for await (const chunk of stream) {
      const text = chunk.choices[0]?.delta?.content
      if (text) onText(text)
    }
  })()

  return { done, handle }
}

/** Vendor errors are wordy and inconsistent; reduce them to something actionable. */
export function aiErrorMessage(err: unknown): string {
  if (err instanceof Anthropic.APIError || err instanceof OpenAI.APIError) {
    switch (err.status) {
      case 401:
        return 'That API key was rejected. Check it in Settings.'
      case 403:
        return "That key doesn't have access to this model."
      case 404:
        return 'That model does not exist for this key. Pick another in Settings.'
      case 429:
        return 'Rate limited or out of credit. Wait a moment, or check your billing.'
      default:
        if (err.status && err.status >= 500) return 'The provider is having trouble. Try again.'
        return err.message
    }
  }
  if ((err as Error)?.name === 'AbortError') return ''
  // A browser CORS failure surfaces as a bare network error with no status.
  if (err instanceof Anthropic.APIConnectionError || err instanceof OpenAI.APIConnectionError) {
    return 'Could not reach the provider. Check your connection.'
  }
  return (err as Error)?.message ?? 'Something went wrong.'
}
