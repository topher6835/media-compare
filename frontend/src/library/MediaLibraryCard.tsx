import { memo, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import type { MediaLibraryItem } from '../api/mediaLibrary.ts'
import type { ThumbnailWork } from './mediaLibraryState.ts'
import { formatGroupFileCount } from './mediaLibraryGroupState.ts'
import { ExactBadge } from './ExactBadge.tsx'

interface CardProps {
  item: MediaLibraryItem
  mode: 'items' | 'groups'
  fromLibraryKey?: string
  onOpen?: () => void
  currentItemCount?: number
  work?: ThumbnailWork
  observeCard: (node: HTMLElement, id: number) => () => void
  onRetry: (item: MediaLibraryItem, purpose: 'generation' | 'repair') => void
  onRepairLoaded: (id: number) => void
}

export const MediaLibraryCard = memo(function MediaLibraryCard({ item, mode, fromLibraryKey, onOpen,
  currentItemCount, work, observeCard, onRetry, onRepairLoaded }: CardProps) {
  const card = useRef<HTMLElement>(null)
  const [brokenUrl, setBrokenUrl] = useState<string | null>(null)
  const url = item.thumbnail.url
  const broken = url !== null && brokenUrl === url
  const active = work && ['scheduling', 'awaiting', 'deferred'].includes(work.phase)
  const repairing = active && work.purpose === 'repair'
  const retryRequest = repairing && work.reload > 0
  const imageUrl = url && retryRequest
    ? `${url}${url.includes('?') ? '&' : '?'}repair=${work.startedAt}-${work.reload}` : url
  const detailUrl = `/library/items/${item.fileEntryId}?from=${mode}`

  useEffect(() => {
    const node = card.current
    if (node) return observeCard(node, item.fileEntryId)
  }, [observeCard, item.fileEntryId])

  let label = 'Preview not generated'
  if (broken) label = 'Preview file missing'
  else if (item.generationSupport === 'UNSUPPORTED') label = 'Preview unavailable'
  else if (work?.phase === 'paused') label = 'Preview still pending'
  else if (work?.phase === 'authority-unavailable') label = 'Prepare/Accept on Sources for a finite preview request'
  else if (work?.phase === 'failed') label = 'Preview request failed'
  if (active) {
    label = work.phase === 'deferred' ? 'Waiting for preview queue'
      : repairing ? 'Repairing preview…' : 'Preparing preview…'
  }

  return (
    <article ref={card} className="library-card" data-file-entry-id={item.fileEntryId} aria-label={currentItemCount === undefined
      ? item.displayName : `${item.displayName}, ${formatGroupFileCount(currentItemCount)}`}>
      <div className="library-tile">
        <div className="library-tile-content">
          {url && (!broken || retryRequest) && (
            <img
              key={imageUrl}
              src={imageUrl ?? undefined}
              alt={item.displayName}
              loading="lazy"
              decoding="async"
              className={broken ? 'library-image-retry' : undefined}
              onError={(event) => {
                if (event.currentTarget.isConnected) setBrokenUrl(url)
              }}
              onLoad={(event) => {
                if (!event.currentTarget.isConnected) return
                setBrokenUrl(null)
                if (work?.purpose === 'repair') onRepairLoaded(item.fileEntryId)
              }}
            />
          )}
          {(!url || broken) && (
            <div className="library-placeholder">
              <svg viewBox="0 0 32 32" aria-hidden="true" className="library-image-symbol">
                <rect x="4" y="4" width="24" height="24" rx="3" />
                <circle cx="12" cy="12" r="2" />
                <path d="m5 24 7-7 5 5 5-9 6 11" />
              </svg>
              <span>{label}</span>
              {active && <span className="library-pulse" aria-hidden="true" />}
              {item.generationSupport === 'SUPPORTED' && !active
                && (broken || work?.phase === 'paused' || work?.phase === 'failed' || work?.phase === 'authority-unavailable') && (
                <button
                  type="button"
                  className="library-retry-button"
                  aria-label={`${broken ? 'Repair preview' : 'Retry preview'} for ${item.displayName}`}
                  onClick={() => onRetry(item, broken ? 'repair' : 'generation')}
                >
                  {broken ? 'Repair preview' : work?.phase === 'authority-unavailable' ? 'Request preview after Prepare' : 'Retry preview'}
                </button>
              )}
            </div>
          )}
          <Link className="library-card-main-link" to={detailUrl} state={{ fromLibraryKey }} onClick={onOpen}
            aria-label={`View details for ${item.displayName}`} />
        </div>
        {currentItemCount !== undefined && (
          <span className="library-group-count" aria-hidden="true">{formatGroupFileCount(currentItemCount)}</span>
        )}
      </div>
      <div className="library-card-caption">
        <h2 title={item.relativePath}><Link to={detailUrl} state={{ fromLibraryKey }} onClick={onOpen}>{item.displayName}</Link></h2>
        <p className="library-source" title={`${item.sourceName} · ${item.relativePath}`}>
          {item.sourceName}
          <span className="library-accessible-path"> · {item.relativePath}</span>
        </p>
        <p className="library-format">
          {(item.extensionKey ?? 'Unknown extension').toUpperCase()} · {item.sizeBytes.toLocaleString()} bytes
          {item.format && ` · ${item.format.toUpperCase()}`}
          {item.encodedWidth !== null && item.encodedHeight !== null
            && ` · ${item.encodedWidth} × ${item.encodedHeight}`}
        </p>
        {work?.phase === 'authority-unavailable' && <p><Link to="/sources">Prepare/Accept on Sources</Link>, then request this finite preview. Cached previews need no authority.</p>}
        <ExactBadge exactSet={item.exactSet} />
      </div>
    </article>
  )
})
