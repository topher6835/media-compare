import { useLocation, useSearchParams } from 'react-router-dom'
import { MediaLibraryGroupView, MediaLibraryItemView } from './MediaLibraryViews.tsx'
import { libraryView, type LibraryView } from './libraryView.ts'
import { libraryReturnKey } from './librarySession.ts'

export function MediaLibraryPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const location = useLocation()
  const view = libraryView(searchParams.get('view'))
  const restoreKey = libraryReturnKey(location.state, location.key)

  function selectView(next: LibraryView) {
    setSearchParams((current) => {
      const updated = new URLSearchParams(current)
      updated.set('view', next)
      return updated
    })
  }

  return (
    <section className="library-page" aria-labelledby="library-heading">
      <header className="library-header">
        <p className="eyebrow">Media catalog</p>
        <h1 id="library-heading">Media Library</h1>
        <div className="library-view-switch" role="group" aria-label="Library view">
          <button type="button" aria-pressed={view === 'items'} onClick={() => selectView('items')}>Items</button>
          <button type="button" aria-pressed={view === 'groups'} onClick={() => selectView('groups')}>Groups</button>
        </div>
        <p className="page-intro">{view === 'items'
          ? 'Browse current catalog images and their small previews, with source context for each file.'
          : 'Exact groups combine current files with identical content. Single files remain one-file groups.'}</p>
      </header>

      {view === 'items'
        ? <MediaLibraryItemView key={location.key} historyKey={location.key} restoreKey={restoreKey} />
        : <MediaLibraryGroupView key={location.key} historyKey={location.key} restoreKey={restoreKey} />}
    </section>
  )
}
