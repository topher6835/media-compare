import { useCallback, useEffect, useEffectEvent, useRef, useState } from 'react'
import { getSources, sourceAuthorityWindows } from '../api/sources.ts'
import { scheduleMediaLibraryThumbnails, type MediaLibraryItem, type ThumbnailScheduleStatus } from '../api/mediaLibrary.ts'
import {
  applyScheduleStatus, newThumbnailWork, reconcileThumbnailWork,
  visibleAwaitingPages, visibleRepairCandidates, visibleSchedulingCandidates,
  type ThumbnailPageSegment, type ThumbnailWorkById,
} from './mediaLibraryState.ts'

interface ThumbnailOptions {
  items: MediaLibraryItem[]
  pages: ThumbnailPageSegment[]
  visible: ReadonlySet<number>
  refreshPage: (cursor: number | null, signal: AbortSignal) => Promise<void>
}

// Called only for an explicit retry of terminal scheduling authority loss.
export async function scheduleExplicitThumbnailRetry(
  item: Pick<MediaLibraryItem, 'fileEntryId' | 'sourceId'>,
  signal: AbortSignal,
): Promise<ThumbnailScheduleStatus> {
  const source = (await getSources(signal)).find((candidate) => candidate.id === item.sourceId)
  if (!source) throw new Error('Source unavailable')
  const requiresAuthority = source.filesystemProfile === 'EXFAT'
  const response = await scheduleMediaLibraryThumbnails([item.fileEntryId], signal,
    requiresAuthority ? sourceAuthorityWindows(source) : undefined)
  const status = response.results[0]?.status
  // A rejected finite exFAT item needs new explicit acceptance, not an automatic queue retry.
  return !status || requiresAuthority && status === 'QUEUE_FULL' ? 'AUTHORITY_UNAVAILABLE' : status
}

export function useVisibleThumbnails({ items, pages, visible, refreshPage }: ThumbnailOptions) {
  const [work, setWork] = useState<ThumbnailWorkById>({})
  const scheduleBusy = useRef(false)
  const refreshes = useRef(new Map<number | null, AbortController>())
  const lastRefresh = useRef(new Map<number | null, number>())
  const needsClock = items.some((item) => visible.has(item.fileEntryId)
    && item.thumbnail.state === 'MISSING' && item.generationSupport === 'SUPPORTED'
    && !work[item.fileEntryId])
    || Object.values(work).some((entry) =>
      ['scheduling', 'awaiting', 'deferred'].includes(entry.phase))

  // One short clock batches visibility changes. It performs no network work when
  // there are no visible candidates/awaiting items; every work cycle expires.
  const tick = useEffectEvent((lifetime: AbortSignal) => {
    const now = Date.now()
    const current = reconcileThumbnailWork(items, work, now)
    if (Object.keys(current).length !== Object.keys(work).length
      || Object.entries(current).some(([id, value]) => value !== work[Number(id)])) {
      setWork(current)
    }

    const candidates = new Set([
      ...visibleSchedulingCandidates(items, visible, current, now),
      ...visibleRepairCandidates(items, visible, current, now),
    ])
    const batch = items.filter((item) => candidates.has(item.fileEntryId)).slice(0, 100)
    if (batch.length && !scheduleBusy.current) {
      scheduleBusy.current = true
      setWork((previous) => {
        const next = { ...previous }
        for (const item of batch) {
          next[item.fileEntryId] = { ...(current[item.fileEntryId]
            ?? newThumbnailWork(item, 'generation', now)), phase: 'scheduling' }
        }
        return next
      })
      // A hung request must also release the client's in-flight state.
      const signal = AbortSignal.any([lifetime, AbortSignal.timeout(10_000)])
      void scheduleMediaLibraryThumbnails(batch.map((item) => item.fileEntryId), signal)
        .then((response) => {
          if (lifetime.aborted) return
          const statuses = new Map(response.results.map((result) => [result.fileEntryId, result.status]))
          setWork((previous) => {
            const next = { ...previous }
            for (const item of batch) {
              const entry = next[item.fileEntryId]
              if (!entry || entry.phase !== 'scheduling') continue
              const status = statuses.get(item.fileEntryId)
              next[item.fileEntryId] = status
                ? applyScheduleStatus(entry, status, Date.now())
                : { ...entry, phase: 'failed' }
            }
            return next
          })
        })
        .catch(() => {
          if (lifetime.aborted) return
          setWork((previous) => {
            const next = { ...previous }
            for (const item of batch) {
              const entry = next[item.fileEntryId]
              if (entry?.phase === 'scheduling') next[item.fileEntryId] = { ...entry, phase: 'failed' }
            }
            return next
          })
        })
        .finally(() => { scheduleBusy.current = false })
    }

    const relevant = visibleAwaitingPages(pages, visible, current)
    const relevantCursors = new Set(relevant.map((page) => page.cursor))
    for (const [cursor, controller] of refreshes.current) {
      if (!relevantCursors.has(cursor)) {
        controller.abort()
        refreshes.current.delete(cursor)
      }
    }
    for (const page of relevant) {
      const cursor = page.cursor
      if (refreshes.current.has(cursor) || now - (lastRefresh.current.get(cursor) ?? 0) < 1_000) continue
      const controller = new AbortController()
      refreshes.current.set(cursor, controller)
      lastRefresh.current.set(cursor, now)
      const signal = AbortSignal.any([lifetime, controller.signal, AbortSignal.timeout(5_000)])
      void refreshPage(cursor, signal)
        .catch(() => { /* A transient read failure can retry within the bounded window. */ })
        .finally(() => {
          if (refreshes.current.get(cursor) === controller) refreshes.current.delete(cursor)
        })
    }

    // Repair retries use the returned immutable URL with a temporary request token.
    // They do not poll the projection, which is already PUBLISHED.
    for (const item of items) {
      const entry = current[item.fileEntryId]
      if (!visible.has(item.fileEntryId) || entry?.purpose !== 'repair'
        || entry.phase !== 'awaiting' || now < entry.retryAt) continue
      setWork((previous) => {
        const latest = previous[item.fileEntryId]
        if (latest !== entry) return previous
        return { ...previous, [item.fileEntryId]: { ...entry, reload: entry.reload + 1, retryAt: now + 1_000 } }
      })
    }
  })

  useEffect(() => {
    if (!needsClock) return
    const controller = new AbortController()
    const activeRefreshes = refreshes.current
    const timer = window.setInterval(() => tick(controller.signal), 250)
    return () => {
      window.clearInterval(timer)
      controller.abort()
      for (const refresh of activeRefreshes.values()) refresh.abort()
      activeRefreshes.clear()
    }
  }, [needsClock])

  const retry = useCallback((item: MediaLibraryItem, purpose: 'generation' | 'repair') => {
    if (item.generationSupport !== 'SUPPORTED') return
    if (work[item.fileEntryId]?.phase === 'authority-unavailable') {
      if (scheduleBusy.current) return
      scheduleBusy.current = true
      const entry = { ...newThumbnailWork(item, purpose, Date.now()), phase: 'scheduling' as const }
      setWork((previous) => ({ ...previous, [item.fileEntryId]: entry }))
      // Explicit finite request only. Source projection is re-fetched; this never prepares a root.
      const signal = AbortSignal.timeout(10_000)
      void scheduleExplicitThumbnailRetry(item, signal).then((status) => {
        setWork((previous) => ({ ...previous, [item.fileEntryId]: applyScheduleStatus(entry, status, Date.now()) }))
      }).catch(() => {
        setWork((previous) => ({ ...previous, [item.fileEntryId]: { ...entry, phase: 'authority-unavailable' } }))
      }).finally(() => { scheduleBusy.current = false })
      return
    }
    setWork((previous) => ({ ...previous, [item.fileEntryId]: newThumbnailWork(item, purpose, Date.now()) }))
  }, [work])

  const repairLoaded = useCallback((id: number) => {
    setWork((previous) => {
      if (previous[id]?.purpose !== 'repair') return previous
      const next = { ...previous }
      delete next[id]
      return next
    })
  }, [])

  return { work, retry, repairLoaded }
}
