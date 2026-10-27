import { useState } from 'react'
import { openSession, catalogFailureMessage, type ManagementStatus } from '../api/startup.ts'
import { FolderBrowser } from './FolderBrowser.tsx'

export function SessionStartupPage({ status, onStatus }: { status?: ManagementStatus; onStatus?: (status: ManagementStatus) => void }) {
  const [browsing, setBrowsing] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const active = submitting || status?.processAlive === true
  const starting = status?.catalogState === 'CREATED' || status?.catalogState === 'STARTING'

  async function select(path: string) {
    setSubmitting(true)
    setError(null)
    try {
      const next = await openSession(path)
      onStatus?.(next)
      setBrowsing(false)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'The Session could not be opened.')
    } finally { setSubmitting(false) }
  }

  return <section className="home-page session-startup">
    <p className="eyebrow">Sessions</p>
    <h1>Welcome to Media Compare</h1>
    <p className="page-intro">A Session keeps your catalog and previews together, separate from your original media.</p>
    {starting && <p role="status">Opening Session… Initializing the catalog and checking ownership. This may take a moment.</p>}
    {status?.catalogState === 'STOPPING' && <p role="status">The catalog is shutting down. Waiting for its process to exit…</p>}
    {status?.catalogState === 'FAILED' && <p role="alert">{catalogFailureMessage(status.failureCode)} {status.processAlive && 'Waiting for the previous process to fully exit.'}</p>}
    {error && <p role="alert">{error}</p>}
    {browsing ? <FolderBrowser mode="open" onSelect={(path) => { void select(path) }} onCancel={() => setBrowsing(false)} busy={active} /> : <div className="home-actions">
      <article>
        <h2>New Session</h2>
        <p>Choose where to save a new catalog. Available in a later update.</p>
        <button type="button" disabled>New Session</button>
      </article>
      <article>
        <h2>Open Existing Session</h2>
        <p>Continue with a Session you already have.</p>
        <button type="button" disabled={active} onClick={() => { setError(null); setBrowsing(true) }}>Open Existing Session</button>
      </article>
      <article><h2>Recent Sessions</h2><p>Recent Session history is coming in a later update.</p></article>
    </div>}
  </section>
}
