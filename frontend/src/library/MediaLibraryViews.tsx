import { useMemo } from 'react'
import { MediaLibraryGrid, type LibraryCardEntry } from './MediaLibraryGrid.tsx'
import { flattenLibraryGroupPages } from './mediaLibraryGroupState.ts'
import { flattenLibraryPages, groupThumbnailSegments, itemThumbnailSegments } from './mediaLibraryState.ts'
import { useMediaLibraryGroupPages } from './useMediaLibraryGroupPages.ts'
import { useMediaLibraryPages } from './useMediaLibraryPages.ts'

export function MediaLibraryItemView() {
  const { pages, ...pagination } = useMediaLibraryPages()
  const cards = useMemo<LibraryCardEntry[]>(() => flattenLibraryPages(pages).map((item) => ({ item })), [pages])
  const segments = useMemo(() => itemThumbnailSegments(pages), [pages])
  return <MediaLibraryGrid mode="items" cards={cards} segments={segments} {...pagination} />
}

export function MediaLibraryGroupView() {
  const { pages, ...pagination } = useMediaLibraryGroupPages()
  const cards = useMemo<LibraryCardEntry[]>(() => flattenLibraryGroupPages(pages).map((group) => ({
    item: group.representative, currentItemCount: group.currentItemCount,
  })), [pages])
  const segments = useMemo(() => groupThumbnailSegments(pages), [pages])
  return <MediaLibraryGrid mode="groups" cards={cards} segments={segments} {...pagination} />
}
