import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

/**
 * Renders a note body as markdown.
 *
 * react-markdown does not pass raw HTML through unless you explicitly add
 * rehype-raw, and we deliberately don't: note bodies sync between devices and a
 * note is the last place that should be able to run script.
 */
export function Markdown({ source }: { source: string }) {
  return (
    <div className="markdown">
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
          img: ({ src, alt }) => <img src={typeof src === 'string' ? src : ''} alt={alt ?? ''} loading="lazy" />,
        }}
      >
        {source}
      </ReactMarkdown>
    </div>
  )
}
