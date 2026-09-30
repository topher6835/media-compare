import { useEffect, useMemo, useRef } from 'react'
import { Link } from 'react-router-dom'
import type { MediaLibraryItem } from '../api/mediaLibrary.ts'
import { MediaLibraryCard } from './MediaLibraryCard.tsx'
import type { ThumbnailPageSegment } from './mediaLibraryState.ts'
import { useCardVisibility } from './useCardVisibility.ts'
import { useVisibleThumbnails } from './useVisibleThumbnails.ts'

export interface LibraryCardEntry {
  item: MediaLibraryItem
  currentItemCount?: number
}

interface GridProps {
  mode: 'items' | 'groups'
  cards: LibraryCardEntry[]
  segments: ThumbnailPageSegment[]
  loading: boolean
  error: boolean
  nextCursor: number | null
  loadMore: () => Promise<void>
  refreshPage: (cursor: number | null, signal: AbortSignal) => Promise<void>
  retry: () => void
}

/** One visible-card thumbnail lifecycle and one progressive grid for the active view. */
export function MediaLibraryGrid({ mode, cards, segments, loading, error, nextCursor,
  loadMore, refreshPage, retry }: GridProps) {
  const items = useMemo(() => cards.map((card) => card.item), [cards])
  const { visible, observeCard } = useCardVisibility()
  const thumbnails = useVisibleThumbnails({ items, pages: segments, visible, refreshPage })
  const sentinel = useRef<HTMLDivElement>(null)
  const initial = segments.length === 0
  const groups = mode === 'groups'

  useEffect(() => {
    const node = sentinel.current
    if (!node || nextCursor === null || loading || error
      || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void loadMore()
    }, { rootMargin: '300px 0px', threshold: 0 })
    observer.observe(node)
    return () => observer.disconnect()
  }, [nextCursor, loading, error, loadMore])

  return (
    <>
      {initial && loading && <div className="state-panel" role="status">
        {groups ? 'Loading media groups…' : 'Loading catalog images…'}
      </div>}
      {initial && error && (
        <div className="state-panel error-panel" role="alert">
          <h2>{groups ? 'Unable to load groups' : 'Unable to load the library'}</h2>
          <p>Check that the backend is running and try again.</p>
          <button type="button" onClick={retry}>Try again</button>
        </div>
      )}
      {!initial && cards.length === 0 && !loading && !error && (
        <div className="state-panel empty-panel">
          <h2>{groups ? 'No current media groups' : 'No current catalog images'}</h2>
          <p>{groups
            ? 'Groups appear here when current catalog images are available.'
            : 'Images appear here when the catalog has current indexed image files.'}</p>
          <Link className="secondary-link" to="/sources">View Sources</Link>
        </div>
      )}

      {cards.length > 0 && (
        <>
          <p className="library-count">{cards.length.toLocaleString()} {groups ? 'groups' : 'images'} loaded</p>
          <div className="library-grid">
            {cards.map(({ item, currentItemCount }) => (
              <MediaLibraryCard
                key={item.fileEntryId}
                item={item}
                currentItemCount={currentItemCount}
                work={thumbnails.work[item.fileEntryId]}
                observeCard={observeCard}
                onRetry={thumbnails.retry}
                onRepairLoaded={thumbnails.repairLoaded}
              />
            ))}
          </div>
        </>
      )}

      <div ref={sentinel} className="library-pagination">
        {!initial && loading && <p role="status">Loading more {groups ? 'groups' : 'images'}…</p>}
        {!initial && error && <p role="alert">More {groups ? 'groups' : 'images'} could not be loaded. Try again.</p>}
        {!initial && nextCursor !== null && (
          <button className="library-load-more" type="button" disabled={loading} onClick={error ? retry : loadMore}>
            {error ? 'Retry loading more' : 'Load more'}
          </button>
        )}
        {!initial && !loading && !error && nextCursor === null && cards.length > 0 && (
          <p>End of current catalog</p>
        )}
      </div>
    </>
  )
}
