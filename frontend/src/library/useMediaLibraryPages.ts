import { useCallback, useEffect, useRef, useState } from 'react'
import { getMediaLibraryItems } from '../api/mediaLibrary.ts'
import { appendLibraryPage, refreshLibraryPage, type LoadedLibraryPage } from './mediaLibraryState.ts'

export function useMediaLibraryPages() {
  const [pages, setPages] = useState<LoadedLibraryPage[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [initialAttempt, setInitialAttempt] = useState(0)
  const moreRequest = useRef<AbortController | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    getMediaLibraryItems(null, controller.signal)
      .then((page) => {
        if (!controller.signal.aborted) setPages(appendLibraryPage([], null, page))
      })
      .catch(() => {
        if (!controller.signal.aborted) setError(true)
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [initialAttempt])

  useEffect(() => () => { moreRequest.current?.abort() }, [])

  const nextCursor = pages.at(-1)?.nextCursor ?? null
  const loadMore = useCallback(async () => {
    if (nextCursor === null || loading || moreRequest.current) return
    const controller = new AbortController()
    moreRequest.current = controller
    setLoading(true)
    setError(false)
    try {
      const page = await getMediaLibraryItems(nextCursor, controller.signal)
      if (!controller.signal.aborted) {
        setPages((current) => appendLibraryPage(current, nextCursor, page))
      }
    } catch {
      if (!controller.signal.aborted) setError(true)
    } finally {
      moreRequest.current = null
      if (!controller.signal.aborted) setLoading(false)
    }
  }, [nextCursor, loading])

  const refreshPage = useCallback(async (cursor: number | null, signal: AbortSignal) => {
    const page = await getMediaLibraryItems(cursor, signal)
    if (!signal.aborted) setPages((current) => refreshLibraryPage(current, cursor, page))
  }, [])

  function retry() {
    if (pages.length > 0) {
      void loadMore()
    } else {
      setError(false)
      setLoading(true)
      setInitialAttempt((attempt) => attempt + 1)
    }
  }

  return { pages, loading, error, nextCursor, loadMore, refreshPage, retry }
}
