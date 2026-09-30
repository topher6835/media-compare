import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError } from '../api/http.ts'
import { getMediaLibraryItem, type MediaLibraryItem } from '../api/mediaLibrary.ts'
import { ExactBadge } from './ExactBadge.tsx'

export function MediaLibraryItemDetailPage() {
  const { fileEntryId = '' } = useParams()
  const id = Number(fileEntryId)
  const [item, setItem] = useState<MediaLibraryItem | null>(null)
  const [failure, setFailure] = useState<'missing' | 'server' | null>(null)
  const [brokenPreview, setBrokenPreview] = useState(false)

  useEffect(() => {
    if (!Number.isSafeInteger(id) || id <= 0) return
    const controller = new AbortController()
    getMediaLibraryItem(id, controller.signal).then((loaded) => {
      setItem(loaded)
      setFailure(null)
      setBrokenPreview(false)
    }).catch((error: unknown) => {
      if (controller.signal.aborted) return
      setFailure(error instanceof ApiError && error.status === 404 ? 'missing' : 'server')
    })
    return () => controller.abort()
  }, [id])

  const invalid = !Number.isSafeInteger(id) || id <= 0
  return <div className="library-item-detail">
    <Link className="back-link" to="/library">← Library</Link>
    {invalid || failure === 'missing' ? <div className="state-panel empty-panel">
      <h1>Item not found</h1><p>This catalog image is unavailable.</p>
    </div> : failure === 'server' ? <div className="state-panel error-panel" role="alert">
      <h1>Unable to load item</h1><p>Check that the backend is running and try again.</p>
    </div> : !item || item.fileEntryId !== id ? <div className="state-panel" role="status">Loading item…</div>
      : <MediaLibraryItemDetailContent item={item} brokenPreview={brokenPreview}
        onPreviewError={() => setBrokenPreview(true)} />}
  </div>
}

export function MediaLibraryItemDetailContent({ item, brokenPreview, onPreviewError }: {
  item: MediaLibraryItem
  brokenPreview: boolean
  onPreviewError: () => void
}) {
  const previewUrl = item.thumbnail.url
  return <>
        <p className="eyebrow">Library item · FileEntry #{item.fileEntryId}</p>
        <h1>{item.displayName}</h1>
        <div className="item-detail-layout">
          <div className="item-detail-preview">
            {previewUrl && !brokenPreview ? <img src={previewUrl} alt={item.displayName}
              onError={onPreviewError} />
              : <div className="library-placeholder">
                <svg viewBox="0 0 32 32" aria-hidden="true" className="library-image-symbol">
                  <rect x="4" y="4" width="24" height="24" rx="3" />
                  <circle cx="12" cy="12" r="2" />
                  <path d="m5 24 7-7 5 5 5-9 6 11" />
                </svg>
                <span>{brokenPreview ? 'Preview file missing'
                  : item.generationSupport === 'UNSUPPORTED' ? 'Preview unavailable for this format'
                    : 'Preview not generated'}</span>
              </div>}
            <p>Available preview uses the current small thumbnail (up to 320px).</p>
          </div>
          <div className="item-detail-facts">
            <ExactBadge exactSet={item.exactSet} />
            <dl className="detail-summary">
              <div><dt>Extension</dt><dd>{item.extensionKey?.toUpperCase() ?? 'Unavailable'}</dd></div>
              <div><dt>Size</dt><dd>{item.sizeBytes.toLocaleString()} bytes</dd></div>
              <div><dt>Decoded format</dt><dd>{item.format?.toUpperCase() ?? 'Unavailable'}</dd></div>
              <div><dt>Encoded dimensions</dt><dd>{item.encodedWidth && item.encodedHeight
                ? `${item.encodedWidth} × ${item.encodedHeight}` : 'Unavailable'}</dd></div>
              <div><dt>Source</dt><dd>{item.sourceName} · #{item.sourceId}</dd></div>
              <div><dt>Source paths</dt><dd>{item.sourceCount}</dd></div>
            </dl>
            <h2>Full path</h2>
            <p className="item-full-path">{item.absolutePath ?? 'Path unavailable from current trusted catalog route'}</p>
          </div>
        </div>
      </>
}
