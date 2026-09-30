import type { LoadedLibraryGroupPage } from './mediaLibraryGroupState.ts'
import type { LoadedLibraryPage } from './mediaLibraryState.ts'
import type { LibraryView } from './libraryView.ts'

interface LibrarySnapshot {
  mode: LibraryView
  pages: Array<LoadedLibraryPage | LoadedLibraryGroupPage>
  cardId: number | null
  scrollY: number
}

const snapshots = new Map<string, LibrarySnapshot>()
const MAX_SNAPSHOTS = 8

export function libraryReturnKey(state: unknown, locationKey: string): string {
  if (state && typeof state === 'object' && 'restoreLibraryKey' in state
    && typeof state.restoreLibraryKey === 'string') return state.restoreLibraryKey
  return locationKey
}

export function getLibrarySnapshot(mode: 'items', key: string): (LibrarySnapshot & { pages: LoadedLibraryPage[] }) | null
export function getLibrarySnapshot(mode: 'groups', key: string): (LibrarySnapshot & { pages: LoadedLibraryGroupPage[] }) | null
export function getLibrarySnapshot(mode: LibraryView, key: string): LibrarySnapshot | null
export function getLibrarySnapshot(mode: LibraryView, key: string): LibrarySnapshot | null {
  const snapshot = snapshots.get(key)
  return snapshot?.mode === mode ? { ...snapshot, pages: [...snapshot.pages] } : null
}

export function rememberLibraryPages(mode: 'items', key: string, pages: LoadedLibraryPage[]): void
export function rememberLibraryPages(mode: 'groups', key: string, pages: LoadedLibraryGroupPage[]): void
export function rememberLibraryPages(mode: LibraryView, key: string,
  pages: LoadedLibraryPage[] | LoadedLibraryGroupPage[]): void
export function rememberLibraryPages(mode: LibraryView, key: string,
  pages: LoadedLibraryPage[] | LoadedLibraryGroupPage[]): void {
  const previous = snapshots.get(key)
  snapshots.delete(key)
  snapshots.set(key, { mode, pages: [...pages], cardId: previous?.mode === mode ? previous.cardId : null,
    scrollY: previous?.mode === mode ? previous.scrollY : 0 })
  while (snapshots.size > MAX_SNAPSHOTS) snapshots.delete(snapshots.keys().next().value!)
}

export function rememberLibraryOrigin(mode: LibraryView, key: string, cardId: number, scrollY: number): void {
  const snapshot = snapshots.get(key)
  if (!snapshot || snapshot.mode !== mode) return
  snapshot.cardId = cardId
  snapshot.scrollY = Number.isFinite(scrollY) && scrollY >= 0 ? scrollY : 0
}

export function restoreLibraryPosition(cardIds: number[], snapshot: LibrarySnapshot | null):
    { cardId: number | null; scrollY: number } | null {
  if (!snapshot || snapshot.pages.length === 0) return null
  return { cardId: snapshot.cardId !== null && cardIds.includes(snapshot.cardId) ? snapshot.cardId : null,
    scrollY: snapshot.cardId !== null && !cardIds.includes(snapshot.cardId) ? 0 : snapshot.scrollY }
}
