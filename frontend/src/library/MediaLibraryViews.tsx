import { useMemo } from 'react'
import { MediaLibraryGrid, type LibraryCardEntry } from './MediaLibraryGrid.tsx'
import { flattenLibraryGroupPages } from './mediaLibraryGroupState.ts'
import { flattenLibraryPages, groupThumbnailSegments, itemThumbnailSegments } from './mediaLibraryState.ts'
import { useMediaLibraryGroupPages } from './useMediaLibraryGroupPages.ts'
import { useMediaLibraryPages } from './useMediaLibraryPages.ts'

interface ViewProps { historyKey: string; restoreKey: string }

export function MediaLibraryItemView({ historyKey, restoreKey }: ViewProps) {
  const { pages, ...pagination } = useMediaLibraryPages(historyKey, restoreKey)
  const cards = useMemo<LibraryCardEntry[]>(() => flattenLibraryPages(pages).map((item) => ({ item })), [pages])
  const segments = useMemo(() => itemThumbnailSegments(pages), [pages])
  return <MediaLibraryGrid mode="items" cards={cards} segments={segments}
    historyKey={historyKey} restoreKey={restoreKey} {...pagination} />
}

export function MediaLibraryGroupView({ historyKey, restoreKey }: ViewProps) {
  const { pages, ...pagination } = useMediaLibraryGroupPages(historyKey, restoreKey)
  const cards = useMemo<LibraryCardEntry[]>(() => flattenLibraryGroupPages(pages).map((group) => ({
    item: group.representative, currentItemCount: group.currentItemCount,
  })), [pages])
  const segments = useMemo(() => groupThumbnailSegments(pages), [pages])
  return <MediaLibraryGrid mode="groups" cards={cards} segments={segments}
    historyKey={historyKey} restoreKey={restoreKey} {...pagination} />
}
