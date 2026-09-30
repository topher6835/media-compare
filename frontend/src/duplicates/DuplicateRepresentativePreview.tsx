import { useEffect, useRef, useState } from 'react'
import { getMediaLibraryItem, scheduleMediaLibraryThumbnails, type MediaLibraryItem } from '../api/mediaLibrary.ts'

export function DuplicateRepresentativePreview({ representative }: { representative: MediaLibraryItem | null }) {
  const node = useRef<HTMLDivElement>(null)
  const [visible, setVisible] = useState(false)
  const [item, setItem] = useState(representative)
  const [broken, setBroken] = useState(false)
  const [pending, setPending] = useState(false)

  useEffect(() => {
    const element = node.current
    if (!element) return
    if (typeof IntersectionObserver === 'undefined') {
      const timer = window.setTimeout(() => setVisible(true), 0)
      return () => window.clearTimeout(timer)
    }
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) setVisible(true)
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    if (!visible || !representative || representative.generationSupport !== 'SUPPORTED'
      || representative.thumbnail.state === 'PUBLISHED') return
    const controller = new AbortController()
    let timer: number | undefined
    const started = Date.now()
    async function poll() {
      if (controller.signal.aborted || !representative) return
      try {
        const current = await getMediaLibraryItem(representative.fileEntryId, controller.signal)
        if (controller.signal.aborted) return
        setItem(current)
        if (current.thumbnail.state === 'PUBLISHED' || Date.now() - started >= 30000) {
          setPending(false)
          return
        }
      } catch {
        if (controller.signal.aborted) return
        if (Date.now() - started >= 30000) { setPending(false); return }
      }
      timer = window.setTimeout(poll, 2000)
    }
    void scheduleMediaLibraryThumbnails([representative.fileEntryId], controller.signal)
      .then((response) => {
        if (controller.signal.aborted || response.results[0]?.status === 'QUEUE_FULL') return
        setPending(true)
        timer = window.setTimeout(poll, 1500)
      })
      .catch(() => { /* The group remains readable without a preview. */ })
    return () => {
      controller.abort()
      if (timer !== undefined) window.clearTimeout(timer)
    }
  }, [visible, representative])

  const preview = item?.fileEntryId === representative?.fileEntryId ? item : representative
  return <div className="duplicate-representative" ref={node}>
    {preview?.thumbnail.url && !broken
      ? <img src={preview.thumbnail.url} alt={`Representative exact image: ${preview.displayName}`}
        loading="lazy" onError={() => setBroken(true)} />
      : <div className="library-placeholder">
        <svg viewBox="0 0 32 32" aria-hidden="true" className="library-image-symbol">
          <rect x="4" y="4" width="24" height="24" rx="3" />
          <circle cx="12" cy="12" r="2" />
          <path d="m5 24 7-7 5 5 5-9 6 11" />
        </svg>
        <span>{broken ? 'Preview file missing' : representative
          ? representative.generationSupport === 'SUPPORTED'
            ? pending ? 'Preparing preview…' : 'Preview not generated'
            : 'Preview unavailable'
          : 'No image preview'}</span>
      </div>}
  </div>
}
