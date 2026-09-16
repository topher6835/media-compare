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
        Review exact byte-for-byte duplicates found in the catalog. This view is
        read-only and does not change files.
      </p>
      <Link className="primary-link" to="/duplicates">
        Browse exact duplicates
      </Link>
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
