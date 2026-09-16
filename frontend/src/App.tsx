import { Link, Route, Routes } from 'react-router-dom'

import { DuplicateDetailPage } from './duplicates/DuplicateDetailPage.tsx'
import { DuplicatesPage } from './duplicates/DuplicatesPage.tsx'
import { HomePage } from './HomePage.tsx'

function AppHeader() {
  return (
    <header className="app-header">
      <Link className="brand" to="/">
        Media Compare
      </Link>
      <nav aria-label="Primary navigation">
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
          <Route path="/duplicates" element={<DuplicatesPage />} />
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
