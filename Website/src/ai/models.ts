import type { ProviderId } from './settings'

export type Recommendation = {
  id: string
  label: string
  /** Why this one, for this app specifically. */
  note: string
  inputPerM: number
  outputPerM: number
}

/**
 * Curated for what this app actually does: rewrite, summarise, continue, and
 * restructure a single note. That means short input, short output, and a person
 * waiting on the result — so latency matters and deep reasoning does not.
 *
 * Frontier and "pro"/reasoning models are deliberately absent from this list.
 * They are slower, and on "tighten this paragraph" they spend output tokens on
 * reasoning nobody reads. They remain selectable under "All chat models".
 *
 * Prices are USD per 1M tokens, correct as of July 2026 — indicative only, since
 * both vendors change them. Each provider's pricing page is linked in Settings.
 */
export const RECOMMENDED: Record<ProviderId, Recommendation[]> = {
  anthropic: [
    {
      id: 'claude-haiku-4-5',
      label: 'Haiku 4.5 — fastest, cheapest',
      note: 'Ample for summarising and tidying. Noticeably the quickest to respond.',
      inputPerM: 1,
      outputPerM: 5,
    },
    {
      id: 'claude-sonnet-5',
      label: 'Sonnet 5 — balanced (recommended)',
      note: 'Best prose quality per unit of latency. The default.',
      inputPerM: 3,
      outputPerM: 15,
    },
    {
      id: 'claude-opus-5',
      label: 'Opus 5 — highest quality',
      note: 'Worth it for restructuring a long note; overkill for a one-line rewrite.',
      inputPerM: 5,
      outputPerM: 25,
    },
  ],
  openai: [
    {
      id: 'gpt-5.4-nano',
      label: 'GPT-5.4 nano — fastest, cheapest',
      note: 'Fine for summaries and light edits.',
      inputPerM: 0.2,
      outputPerM: 1.25,
    },
    {
      id: 'gpt-5.4-mini',
      label: 'GPT-5.4 mini — balanced (recommended)',
      note: 'The sensible default: good writing, still fast.',
      inputPerM: 0.75,
      outputPerM: 4.5,
    },
    {
      id: 'gpt-5.4',
      label: 'GPT-5.4 — highest quality',
      note: 'Better at restructuring and critique; slower and ~6x the cost of mini.',
      inputPerM: 2.5,
      outputPerM: 15,
    },
  ],
}

/**
 * Models that exist on the account but cannot answer a chat request, plus ones
 * OpenAI has deprecated. Without this the picker showed 110 entries, most of
 * them audio, image, or embedding models that would simply error.
 */
const NOT_CHAT =
  /audio|realtime|transcribe|tts|whisper|image|dall-e|embedding|moderation|search|instruct|computer-use|deep-research|codex/i

const DEPRECATED =
  /^(gpt-3|gpt-4($|-turbo|-\d)|gpt-4o$|gpt-4\.5|gpt-4\.1-nano|o1|o3-mini|o4-mini)|chat-latest$|preview/i

/** Dated snapshots duplicate their alias and just make the list longer. */
const SNAPSHOT = /-\d{8}$|-\d{4}-\d{2}-\d{2}$/

export function isUsableChatModel(provider: ProviderId, id: string): boolean {
  if (SNAPSHOT.test(id)) return false
  if (provider === 'anthropic') return id.startsWith('claude-')
  if (NOT_CHAT.test(id)) return false
  if (DEPRECATED.test(id)) return false
  return /^(gpt|o\d)/i.test(id)
}

/** The balanced pick — deliberately not the flagship. */
export function defaultModel(provider: ProviderId, available: string[]): string {
  const recommended = RECOMMENDED[provider]
  const balanced = recommended[1] ?? recommended[0]

  for (const candidate of [balanced, ...recommended]) {
    if (available.includes(candidate.id)) return candidate.id
  }
  return available[0] ?? ''
}

export function findRecommendation(
  provider: ProviderId,
  id: string,
): Recommendation | undefined {
  return RECOMMENDED[provider].find((r) => r.id === id)
}

/**
 * A rough monthly figure for realistic use, because per-million-token prices are
 * impossible to intuit. Assumes ~50 actions a month at ~1500 tokens in and ~500
 * out — which is why the honest answer here is that price barely matters.
 */
export function monthlyEstimate(r: Recommendation): string {
  const cost = 0.075 * r.inputPerM + 0.025 * r.outputPerM
  return cost < 0.1 ? 'under 10¢/month' : `about $${cost.toFixed(2)}/month`
}
