export type ImageAlign = 'left' | 'center' | 'right'

export type ImageMeta = {
  /** Rendered width in CSS pixels, or null for the image's natural size. */
  width: number | null
  align: ImageAlign
}

export const DEFAULT_META: ImageMeta = { width: null, align: 'left' }

export const MIN_WIDTH = 60
export const MAX_WIDTH = 2000

/**
 * Layout is carried in the URL fragment: `…/image.png#w=420&align=center`.
 *
 * A fragment is never sent to the server and is ignored when loading an image, so
 * the link keeps working everywhere — paste a note into GitHub and the image still
 * renders, just at its natural size. The alternatives were worse: raw HTML is
 * disabled on purpose, and stuffing the size into the alt text (`![alt|420](…)`)
 * corrupts the one field screen readers actually use.
 */
export function parseImageSrc(src: string): { url: string; meta: ImageMeta } {
  const hash = src.indexOf('#')
  if (hash === -1) return { url: src, meta: { ...DEFAULT_META } }

  const url = src.slice(0, hash)
  const params = new URLSearchParams(src.slice(hash + 1))

  const rawWidth = Number(params.get('w'))
  const width = Number.isFinite(rawWidth) && rawWidth > 0
    ? Math.min(Math.max(Math.round(rawWidth), MIN_WIDTH), MAX_WIDTH)
    : null

  const rawAlign = params.get('align')
  const align: ImageAlign =
    rawAlign === 'center' || rawAlign === 'right' ? rawAlign : 'left'

  return { url, meta: { width, align } }
}

export function buildImageSrc(url: string, meta: ImageMeta): string {
  const params = new URLSearchParams()
  if (meta.width !== null) params.set('w', String(Math.round(meta.width)))
  if (meta.align !== 'left') params.set('align', meta.align)

  const query = params.toString()
  return query ? `${url}#${query}` : url
}

/** Escapes the characters that would break out of `![alt](src)`. */
export function imageMarkdownFor(alt: string, src: string): string {
  const safeAlt = alt.replace(/[[\]]/g, '')
  const safeSrc = /[()\s]/.test(src) ? `<${src}>` : src
  return `![${safeAlt}](${safeSrc})`
}
