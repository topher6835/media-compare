import { useState } from 'react'
import { MediaLibraryGroupView, MediaLibraryItemView } from './MediaLibraryViews.tsx'

type LibraryView = 'items' | 'groups'

export function MediaLibraryPage() {
  const [view, setView] = useState<LibraryView>('items')

  return (
    <section className="library-page" aria-labelledby="library-heading">
      <header className="library-header">
        <p className="eyebrow">Media catalog</p>
        <h1 id="library-heading">Media Library</h1>
        <div className="library-view-switch" role="group" aria-label="Library view">
          <button type="button" aria-pressed={view === 'items'} onClick={() => setView('items')}>Items</button>
          <button type="button" aria-pressed={view === 'groups'} onClick={() => setView('groups')}>Groups</button>
        </div>
        <p className="page-intro">{view === 'items'
          ? 'Browse current catalog images and their small previews, with source context for each file.'
          : 'Exact groups combine current files with identical content. Single files remain one-file groups.'}</p>
      </header>

      {view === 'items' ? <MediaLibraryItemView /> : <MediaLibraryGroupView />}
    </section>
  )
}
