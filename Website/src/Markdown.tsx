import { useMemo, useState } from 'react'
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
  /** Moves the image at [range] so it sits at character offset `to`. */
  onMoveImage?: (range: [number, number], to: number) => void
}

/**
 * Offsets between top-level blocks, which are the only places an image can be
 * re-anchored to. Derived from the source rather than the DOM so the offset can be
 * spliced straight back into the note body.
 */
function blockBoundaries(source: string): number[] {
  const stops = new Set<number>([0])
  const paragraphBreak = /\n[ \t]*\n/g
  let match: RegExpExecArray | null
  while ((match = paragraphBreak.exec(source)) !== null) {
    stops.add(match.index + match[0].length)
  }
  stops.add(source.length)
  return [...stops].sort((a, b) => a - b)
}

/**
 * Renders a note body as markdown.
 *
 * react-markdown does not pass raw HTML through unless you explicitly add
 * rehype-raw, and we deliberately don't: note bodies sync between devices and a
 * note is the last place that should be able to run script.
 */
export function Markdown({ source, onEditImage, onMoveImage }: Props) {
  // Identified by source offset, which survives re-renders and is unique per image
  // even when the same file appears twice in one note.
  const [selected, setSelected] = useState<number | null>(null)
  const [dragging, setDragging] = useState<[number, number] | null>(null)

  const boundaries = useMemo(() => blockBoundaries(source), [source])

  const dropZone = (offset: number) =>
    dragging && onMoveImage ? (
      <DropZone
        key={`drop-${offset}`}
        onDrop={() => {
          onMoveImage(dragging, offset)
          setDragging(null)
        }}
      />
    ) : null

  return (
    <div className="markdown" onClick={() => setSelected(null)}>
      {/* Dropping above everything anchors the image before the first block. */}
      {dropZone(boundaries[0] ?? 0)}

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

          p: ({ node, children }) => {
            const end = node?.position?.end?.offset
            // A drop zone after each paragraph, at the nearest block boundary.
            const target = boundaries.find((b) => end !== undefined && b >= end)
            return (
              <>
                <p>{children}</p>
                {target !== undefined ? dropZone(target) : null}
              </>
            )
          },

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
                onDragStart={
                  canEdit && onMoveImage ? () => setDragging([start, end]) : undefined
                }
                onDragEnd={() => setDragging(null)}
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

function DropZone({ onDrop }: { onDrop: () => void }) {
  const [over, setOver] = useState(false)
  return (
    <div
      className={`image-drop-zone ${over ? 'over' : ''}`}
      onDragOver={(e) => {
        e.preventDefault()
        e.dataTransfer.dropEffect = 'move'
        setOver(true)
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => {
        e.preventDefault()
        setOver(false)
        onDrop()
      }}
    />
  )
}
