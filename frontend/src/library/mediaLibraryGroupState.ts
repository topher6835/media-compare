import type { MediaLibraryGroupPage, MediaLibraryGroupSummary } from '../api/mediaLibrary.ts'

export interface LoadedLibraryGroupPage extends MediaLibraryGroupPage {
  afterRepresentativeFileEntryId: number | null
  endRepresentativeFileEntryId: number | null
}

export function appendLibraryGroupPage(
  pages: LoadedLibraryGroupPage[],
  afterRepresentativeFileEntryId: number | null,
  page: MediaLibraryGroupPage,
): LoadedLibraryGroupPage[] {
  if (pages.some((loaded) => loaded.afterRepresentativeFileEntryId === afterRepresentativeFileEntryId)) return pages
  return [...pages, {
    ...page,
    afterRepresentativeFileEntryId,
    endRepresentativeFileEntryId: page.groups.at(-1)?.representative.fileEntryId ?? afterRepresentativeFileEntryId,
  }]
}

export function flattenLibraryGroupPages(pages: LoadedLibraryGroupPage[]): MediaLibraryGroupSummary[] {
  const groups = new Map<number, MediaLibraryGroupSummary>()
  for (const page of pages) {
    for (const group of page.groups) groups.set(group.representative.fileEntryId, group)
  }
  return [...groups.values()]
}

export function refreshLibraryGroupPage(
  pages: LoadedLibraryGroupPage[],
  afterRepresentativeFileEntryId: number | null,
  refreshed: MediaLibraryGroupPage,
): LoadedLibraryGroupPage[] {
  return pages.map((page) => {
    if (page.afterRepresentativeFileEntryId !== afterRepresentativeFileEntryId) return page
    // Keep the original representative range and navigation cursor. Newly shifted
    // groups belong to a later segment, even if they appear in this refreshed query.
    return { ...page, groups: refreshed.groups.filter((group) =>
      group.representative.fileEntryId > (page.afterRepresentativeFileEntryId ?? 0)
      && group.representative.fileEntryId <= (page.endRepresentativeFileEntryId ?? 0),
    ) }
  })
}

export function formatGroupFileCount(count: number): string {
  return `${count.toLocaleString()} ${count === 1 ? 'file' : 'files'}`
}
