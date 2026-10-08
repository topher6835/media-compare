import type { Source } from '../api/sources.ts'

export interface AuthorityReleaseMessage { windowId: string; text: string; draining: boolean }

// Release is independent of the normal Analyze/global-busy controls.
export function SourceAuthorityControls({ source, pending, message, onRelease }: {
  source: Source
  pending: boolean
  message?: AuthorityReleaseMessage
  onRelease: (windowId: string) => void
}) {
  if (source.filesystemProfile !== 'EXFAT') return null
  return <div>
    <p>Release authority for Sources on this volume before dismounting in VeraCrypt. Releasing active authority may stop affected work.</p>
    {source.liveAuthorityWindowId && <button type="button" disabled={pending}
      onClick={() => onRelease(source.liveAuthorityWindowId!)}>Release authority</button>}
    {message && <p role="status">{message.text}</p>}
    {message?.draining && <button type="button" disabled={pending}
      onClick={() => onRelease(message.windowId)}>Check release</button>}
  </div>
}
