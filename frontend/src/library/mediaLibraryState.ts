import type { MediaLibraryItem, MediaLibraryPage, ThumbnailScheduleStatus } from '../api/mediaLibrary.ts'
import type { LoadedLibraryGroupPage } from './mediaLibraryGroupState.ts'

export const THUMBNAIL_RETRY_MS = 2_000
export const THUMBNAIL_WAIT_MS = 30_000

export interface LoadedLibraryPage extends MediaLibraryPage {
  afterFileEntryId: number | null
  endFileEntryId: number | null
}

export interface ThumbnailPageSegment {
  cursor: number | null
  items: MediaLibraryItem[]
}

export function itemThumbnailSegments(pages: LoadedLibraryPage[]): ThumbnailPageSegment[] {
  return pages.map((page) => ({ cursor: page.afterFileEntryId, items: page.items }))
}

export function groupThumbnailSegments(pages: LoadedLibraryGroupPage[]): ThumbnailPageSegment[] {
  return pages.map((page) => ({ cursor: page.afterRepresentativeFileEntryId,
    items: page.groups.map((group) => group.representative) }))
}

export function appendLibraryPage(
  pages: LoadedLibraryPage[],
  afterFileEntryId: number | null,
  page: MediaLibraryPage,
): LoadedLibraryPage[] {
  if (pages.some((loaded) => loaded.afterFileEntryId === afterFileEntryId)) return pages
  return [...pages, {
    ...page, afterFileEntryId,
    endFileEntryId: page.items.at(-1)?.fileEntryId ?? afterFileEntryId,
  }]
}

export function flattenLibraryPages(pages: LoadedLibraryPage[]): MediaLibraryItem[] {
  const items = new Map<number, MediaLibraryItem>()
  for (const page of pages) {
    for (const item of page.items) items.set(item.fileEntryId, item)
  }
  return [...items.values()]
}

export function refreshLibraryPage(
  pages: LoadedLibraryPage[],
  afterFileEntryId: number | null,
  refreshed: MediaLibraryPage,
): LoadedLibraryPage[] {
  return pages.map((page) => {
    if (page.afterFileEntryId !== afterFileEntryId) return page
    // Keep the original keyset segment and navigation boundary. Eligibility changes
    // may pull later IDs into a fresh response; they belong to later pages.
    return { ...page, items: refreshed.items.filter((item) =>
      item.fileEntryId > (page.afterFileEntryId ?? 0)
      && item.fileEntryId <= (page.endFileEntryId ?? 0),
    ) }
  })
}

export interface ThumbnailWork {
  purpose: 'generation' | 'repair'
  phase: 'scheduling' | 'awaiting' | 'deferred' | 'paused' | 'failed'
  startedAt: number
  retryAt: number
  reload: number
  contentRecordId: number
  url: string | null
}

export type ThumbnailWorkById = Record<number, ThumbnailWork>

export function newThumbnailWork(
  item: MediaLibraryItem,
  purpose: ThumbnailWork['purpose'],
  now: number,
): ThumbnailWork {
  return { purpose, phase: 'deferred', startedAt: now, retryAt: now,
    reload: 0, contentRecordId: item.contentRecordId, url: item.thumbnail.url }
}

function canRetry(work: ThumbnailWork | undefined, now: number): boolean {
  return !work || (work.phase === 'deferred' && now >= work.retryAt
    && now - work.startedAt < THUMBNAIL_WAIT_MS)
}

export function visibleSchedulingCandidates(
  items: MediaLibraryItem[],
  visible: ReadonlySet<number>,
  work: ThumbnailWorkById,
  now: number,
): number[] {
  return items.filter((item) => visible.has(item.fileEntryId)
    && item.generationSupport === 'SUPPORTED'
    && item.thumbnail.state === 'MISSING'
    && canRetry(work[item.fileEntryId], now),
  ).map((item) => item.fileEntryId)
}

export function visibleRepairCandidates(
  items: MediaLibraryItem[],
  visible: ReadonlySet<number>,
  work: ThumbnailWorkById,
  now: number,
): number[] {
  return items.filter((item) => visible.has(item.fileEntryId)
    && item.generationSupport === 'SUPPORTED'
    && item.thumbnail.state === 'PUBLISHED'
    && work[item.fileEntryId]?.purpose === 'repair'
    && canRetry(work[item.fileEntryId], now),
  ).map((item) => item.fileEntryId)
}

export function applyScheduleStatus(
  work: ThumbnailWork,
  status: ThumbnailScheduleStatus,
  now: number,
): ThumbnailWork {
  return { ...work, phase: status === 'QUEUE_FULL' ? 'deferred' : 'awaiting',
    retryAt: now + THUMBNAIL_RETRY_MS }
}

export function reconcileThumbnailWork(
  items: MediaLibraryItem[],
  work: ThumbnailWorkById,
  now: number,
): ThumbnailWorkById {
  const next: ThumbnailWorkById = {}
  for (const item of items) {
    const current = work[item.fileEntryId]
    if (!current || item.generationSupport !== 'SUPPORTED'
      || current.contentRecordId !== item.contentRecordId) continue
    if (current.purpose === 'generation' && item.thumbnail.state === 'PUBLISHED') continue
    if (current.purpose === 'repair' && current.url !== item.thumbnail.url) continue
    next[item.fileEntryId] = now - current.startedAt >= THUMBNAIL_WAIT_MS
      && current.phase !== 'failed' && current.phase !== 'paused'
      ? { ...current, phase: 'paused' } : current
  }
  return next
}

export function visibleAwaitingPages<T extends { items: MediaLibraryItem[] }>(
  pages: T[],
  visible: ReadonlySet<number>,
  work: ThumbnailWorkById,
): T[] {
  return pages.filter((page) => page.items.some((item) =>
    visible.has(item.fileEntryId) && item.thumbnail.state === 'MISSING'
    && work[item.fileEntryId]?.purpose === 'generation'
    && work[item.fileEntryId]?.phase === 'awaiting',
  ))
}
