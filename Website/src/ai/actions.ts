import { buildEditPrompt } from './edits'

export type AiAction = {
  id: string
  label: string
  hint: string
  /**
   * How the result is offered back — replacing the source, added after it, or
   * as a list of individual edits to tick off.
   */
  result: 'replace' | 'append' | 'edits'
  build: (text: string, title: string) => string
}

export const SYSTEM_PROMPT = [
  'You are a writing assistant inside a notes app.',
  'The note is markdown. Preserve the existing markdown structure and any image links exactly as written — never alter a URL.',
  "When images are attached they are the ones embedded in the note, given in order and labelled with their alt text. Read them as part of the note, not as a separate question, and keep referring to them by that alt text. You may edit an image's alt text if it is wrong or missing, but never its URL and never its position in the text.",
  'Return only the requested content. No preamble, no commentary, no code fences around the whole answer.',
  'Match the existing voice and level of detail. If the note is terse notes-to-self, stay terse.',
].join(' ')

/**
 * The edit list is machine-read, so the usual "write like the note" instructions
 * are not just unnecessary here — they actively invite prose around the JSON.
 */
export const EDIT_SYSTEM_PROMPT = [
  'You are a copy editor working inside a notes app.',
  'You reply with JSON only. No preamble, no commentary, no code fences.',
  'The note is markdown. Never alter image links or URLs, and never restructure the note.',
  'Quote the note exactly when you cite it — the app matches your text against the real note character for character, and an inexact quote is discarded.',
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
    hint: 'Each one offered separately — take the ones you want',
    result: 'edits',
    build: (text) => buildEditPrompt(text),
  },
]

export function buildCustomPrompt(instruction: string, text: string): string {
  return `${instruction.trim()}\n\nApply that to the following note content.\n\n---\n${text}`
}
