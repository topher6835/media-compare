import { useEffect, useState } from 'react'
import { Link, useLocation, useParams, useSearchParams } from 'react-router-dom'
import { ApiError } from '../api/http.ts'
import { getExactDuplicateGroup, type ExactDuplicateGroupDetail } from '../api/exactDuplicates.ts'
import { getMediaLibraryItem, type MediaLibraryItem } from '../api/mediaLibrary.ts'
import { filenameFromPath } from '../duplicates/duplicateFormatting.ts'
import { groupPhysicalCopies } from '../duplicates/duplicatePhysicalCopies.ts'
import { ExactBadge } from './ExactBadge.tsx'
import { RevealFileButton } from './RevealFileButton.tsx'

export function MediaLibraryItemDetailPage() {
  const { fileEntryId = '' } = useParams()
  const [searchParams] = useSearchParams()
  const location = useLocation()
  const fromLibraryKey = (location.state as { fromLibraryKey?: unknown } | null)?.fromLibraryKey
  const returnView = searchParams.get('from') === 'groups' ? 'groups' : 'items'
  const id = Number(fileEntryId)
  const [item, setItem] = useState<MediaLibraryItem | null>(null)
  const [failure, setFailure] = useState<'missing' | 'server' | null>(null)
  const [brokenPreview, setBrokenPreview] = useState(false)
  const [exactDetail, setExactDetail] = useState<ExactDuplicateGroupDetail | null>(null)
  const [exactFailure, setExactFailure] = useState(false)

  useEffect(() => {
    if (!Number.isSafeInteger(id) || id <= 0) return
    const controller = new AbortController()
    getMediaLibraryItem(id, controller.signal).then((loaded) => {
      setItem(loaded)
      setExactDetail(null)
      setExactFailure(false)
      setFailure(null)
      setBrokenPreview(false)
    }).catch((error: unknown) => {
      if (controller.signal.aborted) return
      setFailure(error instanceof ApiError && error.status === 404 ? 'missing' : 'server')
    })
    return () => controller.abort()
  }, [id])

  useEffect(() => {
    const digest = item?.fileEntryId === id ? item.exactSet?.digestHex : null
    if (!digest || !item?.exactSet || item.exactSet.physicalCopyCount < 2) return
    const controller = new AbortController()
    getExactDuplicateGroup(digest, { fileCategories: [], extensions: [] }, controller.signal)
      .then((detail) => { setExactDetail(detail); setExactFailure(false) })
      .catch(() => { if (!controller.signal.aborted) setExactFailure(true) })
    return () => controller.abort()
  }, [id, item])

  const invalid = !Number.isSafeInteger(id) || id <= 0
  return <div className="library-item-detail">
    <Link className="back-link" to={`/library?view=${returnView}`}
      state={typeof fromLibraryKey === 'string' ? { restoreLibraryKey: fromLibraryKey } : undefined}>← Library</Link>
    {invalid || failure === 'missing' ? <div className="state-panel empty-panel">
      <h1>Item not found</h1><p>This catalog image is unavailable.</p>
    </div> : failure === 'server' ? <div className="state-panel error-panel" role="alert">
      <h1>Unable to load item</h1><p>Check that the backend is running and try again.</p>
    </div> : !item || item.fileEntryId !== id ? <div className="state-panel" role="status">Loading item…</div>
      : <MediaLibraryItemDetailContent item={item} brokenPreview={brokenPreview}
        exactDetail={exactDetail?.digestHex === item.exactSet?.digestHex ? exactDetail : null}
        exactFailure={exactFailure}
        onPreviewError={() => setBrokenPreview(true)} />}
  </div>
}

export function MediaLibraryItemDetailContent({ item, brokenPreview, onPreviewError, exactDetail, exactFailure }: {
  item: MediaLibraryItem
  brokenPreview: boolean
  onPreviewError: () => void
  exactDetail?: ExactDuplicateGroupDetail | null
  exactFailure?: boolean
}) {
  const previewUrl = item.thumbnail.url
  const copies = exactDetail ? groupPhysicalCopies(exactDetail.occurrences) : []
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
            <div className="reveal-path-row">
              <p className="item-full-path">{item.absolutePath ?? 'Path unavailable from current trusted catalog route'}</p>
              {item.absolutePath && <RevealFileButton fileEntryId={item.fileEntryId} />}
            </div>
          </div>
        </div>
        {item.exactSet && item.exactSet.physicalCopyCount >= 2 && (
          <section className="item-exact-copies" aria-labelledby="item-exact-copies-heading">
            <h2 id="item-exact-copies-heading">Exact copies · {item.exactSet.physicalCopyCount} present</h2>
            {exactFailure ? <p role="alert">Exact-copy paths are unavailable right now.</p>
              : !exactDetail ? <p role="status">Loading exact-copy paths…</p>
                : <ul>{copies.map((copy) => (
                  <li key={copy.fileEntryId}>
                    <div className="item-copy-heading">
                      <strong>{filenameFromPath(copy.occurrences[0].relativePath)}</strong>
                      {copy.fileEntryId === item.fileEntryId && <span className="status-badge present">CURRENT</span>}
                      <span className={`status-badge ${copy.presenceStatus === 'PRESENT' ? 'present' : 'missing'}`}>{copy.presenceStatus}</span>
                    </div>
                    <p>FileEntry #{copy.fileEntryId}</p>
                    {copy.occurrences.map((occurrence) => (
                      <div className="item-copy-route" key={occurrence.membershipId}>
                        <p>Source: {occurrence.sourceName} · #{occurrence.sourceId} · {occurrence.presenceStatus}</p>
                        <div className="reveal-path-row">
                          <p className="item-full-path">{occurrence.absolutePath
                            ?? 'Full path unavailable from current trusted catalog route'}</p>
                          {copy.presenceStatus === 'PRESENT' && occurrence.presenceStatus === 'PRESENT'
                            && occurrence.absolutePath && occurrence === copy.occurrences.find((route) =>
                              route.presenceStatus === 'PRESENT' && route.absolutePath)
                            && <RevealFileButton fileEntryId={copy.fileEntryId} />}
                        </div>
                      </div>
                    ))}
                  </li>
                ))}</ul>}
          </section>
        )}
      </>
}
