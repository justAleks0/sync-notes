import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import {
  MAX_WIDTH,
  MIN_WIDTH,
  buildImageSrc,
  parseImageSrc,
  type ImageAlign,
  type ImageMeta,
} from './imageMeta'

type Props = {
  src: string
  alt: string
  /** Absent in read-only contexts, which renders a plain image. */
  onChange?: (nextSrc: string) => void
  selected: boolean
  onSelect: () => void
}

const PRESETS: { label: string; width: number | null }[] = [
  { label: 'S', width: 240 },
  { label: 'M', width: 420 },
  { label: 'L', width: 680 },
  { label: 'Full', width: null },
]

export function EditableImage({ src, alt, onChange, selected, onSelect }: Props) {
  const { url, meta } = parseImageSrc(src)
  const wrapRef = useRef<HTMLSpanElement>(null)

  // Width while a drag is in flight. Committing on every pointermove would rewrite
  // the note body dozens of times a second.
  const [dragWidth, setDragWidth] = useState<number | null>(null)
  const drag = useRef<{ startX: number; startWidth: number } | null>(null)

  const editable = typeof onChange === 'function'
  const width = dragWidth ?? meta.width

  const commit = (next: Partial<ImageMeta>) => {
    onChange?.(buildImageSrc(url, { ...meta, ...next }))
  }

  function onHandleDown(event: ReactPointerEvent<HTMLSpanElement>) {
    event.preventDefault()
    event.stopPropagation()
    const current = wrapRef.current?.querySelector('img')?.getBoundingClientRect().width ?? 0
    drag.current = { startX: event.clientX, startWidth: current }
    setDragWidth(Math.round(current))
    ;(event.target as HTMLElement).setPointerCapture(event.pointerId)
  }

  function onHandleMove(event: ReactPointerEvent<HTMLSpanElement>) {
    if (!drag.current) return
    const delta = event.clientX - drag.current.startX
    // Dragging the right-hand handle of a centred image moves both edges, so the
    // width has to change at twice the pointer's rate for the edge to track it.
    const scale = meta.align === 'center' ? 2 : 1
    const next = drag.current.startWidth + delta * scale
    setDragWidth(Math.min(Math.max(Math.round(next), MIN_WIDTH), MAX_WIDTH))
  }

  function onHandleUp() {
    if (drag.current && dragWidth !== null) commit({ width: dragWidth })
    drag.current = null
    setDragWidth(null)
  }

  // Deselect on Escape so the toolbar isn't stuck over the text.
  useEffect(() => {
    if (!selected) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onSelect()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [selected, onSelect])

  const image = (
    <img
      src={url}
      alt={alt}
      loading="lazy"
      draggable={false}
      style={width ? { width: `${width}px` } : undefined}
    />
  )

  if (!editable) {
    return (
      <span className={`md-image align-${meta.align}`}>
        <span className="image-frame">{image}</span>
      </span>
    )
  }

  return (
    <span
      ref={wrapRef}
      className={`md-image align-${meta.align} editable ${selected ? 'selected' : ''}`}
      onClick={(e) => {
        e.stopPropagation()
        if (!selected) onSelect()
      }}
    >
      {/* The outer span is a full-width block so text-align can position the image.
          This inner frame shrink-wraps it, which is what the toolbar and handle are
          positioned against — otherwise they anchor to the column edge. */}
      <span className="image-frame">
      {selected && (
        <span className="image-toolbar" onClick={(e) => e.stopPropagation()}>
          {(['left', 'center', 'right'] as ImageAlign[]).map((value) => (
            <button
              key={value}
              className={meta.align === value ? 'active' : ''}
              title={`Align ${value}`}
              onClick={() => commit({ align: value })}
            >
              {value === 'left' ? '⇤' : value === 'center' ? '↔' : '⇥'}
            </button>
          ))}

          <span className="toolbar-sep" />

          {PRESETS.map((preset) => (
            <button
              key={preset.label}
              className={meta.width === preset.width ? 'active' : ''}
              title={preset.width ? `${preset.width}px wide` : 'Natural size'}
              onClick={() => commit({ width: preset.width })}
            >
              {preset.label}
            </button>
          ))}

          <span className="toolbar-size">{width ? `${width}px` : 'auto'}</span>
        </span>
      )}

      {image}

      {selected && (
        <span
          className="resize-handle"
          title="Drag to resize"
          onPointerDown={onHandleDown}
          onPointerMove={onHandleMove}
          onPointerUp={onHandleUp}
          onPointerCancel={onHandleUp}
        />
      )}
      </span>
    </span>
  )
}
