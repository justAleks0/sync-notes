export type AiAction = {
  id: string
  label: string
  hint: string
  /** How the result is offered back — replacing the source, or added after it. */
  result: 'replace' | 'append'
  build: (text: string, title: string) => string
}

export const SYSTEM_PROMPT = [
  'You are a writing assistant inside a notes app.',
  'The note is markdown. Preserve the existing markdown structure and any image links exactly as written — never alter a URL.',
  'Return only the requested content. No preamble, no commentary, no code fences around the whole answer.',
  'Match the existing voice and level of detail. If the note is terse notes-to-self, stay terse.',
].join(' ')

export const ACTIONS: AiAction[] = [
  {
    id: 'improve',
    label: 'Improve writing',
    hint: 'Tighten and clarify without changing meaning',
    result: 'replace',
    build: (text) =>
      `Rewrite the following for clarity and flow. Keep the meaning, the facts, and roughly the same length. Do not add new information.\n\n---\n${text}`,
  },
  {
    id: 'summarise',
    label: 'Summarise',
    hint: 'A short summary added at the top',
    result: 'append',
    build: (text) =>
      `Write a short summary of the following note as markdown bullet points. Five bullets at most.\n\n---\n${text}`,
  },
  {
    id: 'continue',
    label: 'Continue writing',
    hint: 'Carry on from where the note stops',
    result: 'append',
    build: (text, title) =>
      `Continue the following note titled "${title}". Write the next paragraph or section only — do not repeat what is already there.\n\n---\n${text}`,
  },
  {
    id: 'sections',
    label: 'Add structure',
    hint: 'Organise into headings and sections',
    result: 'replace',
    build: (text) =>
      `Reorganise the following note with markdown headings and sections so it is easier to scan. Keep all the existing content — do not delete anything or invent new facts.\n\n---\n${text}`,
  },
  {
    id: 'suggest',
    label: 'Suggest edits',
    hint: 'Notes on what to change, nothing rewritten',
    result: 'append',
    build: (text) =>
      `Review the following note and suggest specific improvements as a markdown checklist. Point at concrete problems — gaps, unclear passages, contradictions. Do not rewrite the note.\n\n---\n${text}`,
  },
]

export function buildCustomPrompt(instruction: string, text: string): string {
  return `${instruction.trim()}\n\nApply that to the following note content.\n\n---\n${text}`
}
