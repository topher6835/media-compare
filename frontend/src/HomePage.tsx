import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'

export function HomePage() {
  const [health, setHealth] = useState({ state: 'pending', label: 'Checking…' })

  useEffect(() => {
    fetch('/api/health')
      .then((response) => {
        if (!response.ok) {
          throw new Error('Health request failed')
        }
        return response.text()
      })
      .then(() => setHealth({ state: 'healthy', label: 'Healthy' }))
      .catch(() => setHealth({ state: 'unavailable', label: 'Unavailable' }))
  }, [])

  return (
    <section className="home-page">
      <p className="eyebrow">Catalog tools</p>
      <h1>Media Compare</h1>
      <p className="page-intro">
        Register local media folders, analyze their catalog contents, and review
        exact byte-for-byte duplicates. Media Compare does not change files.
      </p>
      <div className="home-actions">
        <article>
          <h2>Sources</h2>
          <p>Register a folder and run discovery through exact hashing.</p>
          <Link className="primary-link" to="/sources">
            Index media
          </Link>
        </article>
        <article>
          <h2>Exact Duplicates</h2>
          <p>Browse byte-for-byte matches already available in the catalog.</p>
          <Link className="secondary-link" to="/duplicates">
            Browse exact duplicates
          </Link>
        </article>
      </div>
      <p className="backend-status">
        <span
          className={`status-dot ${health.state}`}
          aria-hidden="true"
        />
        Backend: {health.label}
      </p>
    </section>
  )
}
