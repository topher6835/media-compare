export type LibraryView = 'items' | 'groups'

export function libraryView(value: string | null): LibraryView {
  return value === 'groups' ? 'groups' : 'items'
}
