import { useEffect, useMemo, useRef } from 'react'
import { Link } from 'react-router-dom'
import { MediaLibraryCard } from './MediaLibraryCard.tsx'
import { flattenLibraryPages } from './mediaLibraryState.ts'
import { useCardVisibility } from './useCardVisibility.ts'
import { useMediaLibraryPages } from './useMediaLibraryPages.ts'
import { useVisibleThumbnails } from './useVisibleThumbnails.ts'

export function MediaLibraryPage() {
  const { pages, loading, error, nextCursor, loadMore, refreshPage, retry } = useMediaLibraryPages()
  const items = useMemo(() => flattenLibraryPages(pages), [pages])
  const { visible, observeCard } = useCardVisibility()
  const thumbnails = useVisibleThumbnails({ items, pages, visible, refreshPage })
  const sentinel = useRef<HTMLDivElement>(null)
  const initial = pages.length === 0

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
    <section className="library-page" aria-labelledby="library-heading">
      <header className="library-header">
        <p className="eyebrow">Media catalog</p>
        <h1 id="library-heading">Media Library</h1>
        <p className="page-intro">Browse current catalog images and their small previews, with source context for each file.</p>
      </header>

      {initial && loading && <div className="state-panel" role="status">Loading catalog images…</div>}
      {initial && error && (
        <div className="state-panel error-panel" role="alert">
          <h2>Unable to load the library</h2>
          <p>Check that the backend is running and try again.</p>
          <button type="button" onClick={retry}>Try again</button>
        </div>
      )}
      {!initial && items.length === 0 && !loading && !error && (
        <div className="state-panel empty-panel">
          <h2>No current catalog images</h2>
          <p>Images appear here when the catalog has current image metadata for indexed files.</p>
          <Link className="secondary-link" to="/sources">View Sources</Link>
        </div>
      )}

      {items.length > 0 && (
        <>
          <p className="library-count">{items.length.toLocaleString()} images loaded</p>
          <div className="library-grid">
            {items.map((item) => (
              <MediaLibraryCard
                key={item.fileEntryId}
                item={item}
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
        {!initial && loading && <p role="status">Loading more images…</p>}
        {!initial && error && <p role="alert">More images could not be loaded. Try again.</p>}
        {!initial && nextCursor !== null && (
          <button className="library-load-more" type="button" disabled={loading} onClick={error ? retry : loadMore}>
            {error ? 'Retry loading more' : 'Load more'}
          </button>
        )}
        {!initial && !loading && !error && nextCursor === null && items.length > 0 && (
          <p>End of current catalog</p>
        )}
      </div>
    </section>
  )
}
