import { useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { EditableImage } from './EditableImage'
import { imageMarkdownFor } from './imageMeta'

type Props = {
  source: string
  /**
   * Supplied when the preview should be interactive. Given the exact character range
   * of an image in the source and its replacement text, so the caller can splice it
   * back into the note body.
   */
  onEditImage?: (range: [number, number], markdown: string) => void
}

/**
 * Renders a note body as markdown.
 *
 * react-markdown does not pass raw HTML through unless you explicitly add
 * rehype-raw, and we deliberately don't: note bodies sync between devices and a
 * note is the last place that should be able to run script.
 */
export function Markdown({ source, onEditImage }: Props) {
  // Identified by source offset, which survives re-renders and is unique per image
  // even when the same file appears twice in one note.
  const [selected, setSelected] = useState<number | null>(null)

  return (
    <div className="markdown" onClick={() => setSelected(null)}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          // Open links in the user's browser rather than navigating the app away,
          // which matters most in the Electron shell.
          a: ({ href, children }) => (
            <a href={href} target="_blank" rel="noreferrer noopener">
              {children}
            </a>
          ),

          img: ({ node, src, alt }) => {
            const start = node?.position?.start?.offset
            const end = node?.position?.end?.offset
            const text = typeof src === 'string' ? src : ''
            const label = alt ?? ''
            const canEdit =
              onEditImage !== undefined && start !== undefined && end !== undefined

            return (
              <EditableImage
                src={text}
                alt={label}
                selected={canEdit && selected === start}
                onSelect={() => setSelected(selected === start ? null : (start ?? null))}
                onChange={
                  canEdit
                    ? (nextSrc) =>
                        onEditImage([start, end], imageMarkdownFor(label, nextSrc))
                    : undefined
                }
              />
            )
          },
        }}
      >
        {source}
      </ReactMarkdown>
    </div>
  )
}
