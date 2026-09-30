import { Link, Route, Routes } from 'react-router-dom'

import { DuplicateDetailPage } from './duplicates/DuplicateDetailPage.tsx'
import { DuplicateCleanupPlanPage } from './duplicates/DuplicateCleanupPlanPage.tsx'
import { DuplicatesPage } from './duplicates/DuplicatesPage.tsx'
import { HomePage } from './HomePage.tsx'
import { MediaLibraryPage } from './library/MediaLibraryPage.tsx'
import { MediaLibraryItemDetailPage } from './library/MediaLibraryItemDetailPage.tsx'
import { SourcesPage } from './sources/SourcesPage.tsx'

function AppHeader() {
  return (
    <header className="app-header">
      <Link className="brand" to="/">
        Media Compare
      </Link>
      <nav aria-label="Primary navigation">
        <Link to="/sources">Sources</Link>
        <Link to="/library">Library</Link>
        <Link to="/duplicates">Exact Duplicates</Link>
      </nav>
    </header>
  )
}

function App() {
  return (
    <div className="app-shell">
      <AppHeader />
      <main>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/sources" element={<SourcesPage />} />
          <Route path="/library" element={<MediaLibraryPage />} />
          <Route path="/library/items/:fileEntryId" element={<MediaLibraryItemDetailPage />} />
          <Route path="/duplicates" element={<DuplicatesPage />} />
          <Route path="/duplicates/plan" element={<DuplicateCleanupPlanPage />} />
          <Route
            path="/duplicates/:digestHex"
            element={<DuplicateDetailPage />}
          />
        </Routes>
      </main>
    </div>
  )
}

export default App
