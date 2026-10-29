import { useState } from 'react'
import { openSession, catalogFailureMessage, type ManagementStatus } from '../api/startup.ts'
import { CreationWorkflow, type CreationView } from './creationWorkflow.ts'
import { NewSessionPanel } from './NewSessionPanel.tsx'
import { FolderBrowser } from './FolderBrowser.tsx'

export function SessionStartupPage({ status, onStatus, creation, creationView, managementAvailable = true }: { managementAvailable?: boolean; status?: ManagementStatus; onStatus?: (status: ManagementStatus) => void; creation?: CreationWorkflow; creationView?: CreationView }) {
  const [newSession, setNewSession] = useState(false)
  const [browsing, setBrowsing] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const active = submitting || !managementAvailable || status?.processAlive === true || status?.catalogState === 'STOPPING'
    || status?.catalogState === 'CREATED' || status?.catalogState === 'STARTING'
    || !!creationView && (!creationView.checked || creationView.busy || creationView.activeElsewhere || creationView.stopping || creationView.blocked || !!creationView.error)
  const openActive = submitting || !managementAvailable || (creation && status ? !creation.canOpenExisting(status) : active)
  const starting = status?.catalogState === 'CREATED' || status?.catalogState === 'STARTING'

  async function select(path: string) {
    if (openActive) return
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
    {creationView?.error && !creationView.root && !creationView.blocked && <p role="alert">{creationView.error} Connection checks continue.</p>}
    {error && <p role="alert">{error}</p>}
    {creation && creationView?.error && !creationView.manualOpen && <section>
      <p>Creation recovery is unavailable. Inspect any attempted Session folder before opening it. Outstanding work may still be running; manual Open does not cancel it or resolve its outcome. Management will reject Open while another operation is active.</p>
      <button type="button" disabled={creationView.busy || !managementAvailable} onClick={() => {
        creation.acknowledgeManualOpen(); setNewSession(false)
      }}>I understand; use manual Open</button>
    </section>}
    {creationView?.manualOpen && <p role="status">Manual recovery: New Session remains disabled. Outstanding creation may still continue; inspect the folder before opening a valid Session. Reload to resume creation recovery.</p>}
    {creationView && !creationView.storageAvailable && <p className="startup-note">Browser storage is unavailable. This document still tracks operations, and reload checks retained server operations. If none can be identified after reloading, inspect your selected parent and manually open a valid Session before deliberately creating again.</p>}
    {creationView && !creationView.checked && <p role="status">Checking retained Session operations…</p>}
    {creationView && !creationView.manualOpen && creationView.roots.length > 1 && !creationView.root && <section>
      <h2>Recover a Session operation</h2><p>Multiple retained operations exist. Choose the one to inspect; none will be submitted automatically.</p>
      <ul>{creationView.roots.map((op) => <li key={op.operationId}><button type="button" disabled={creationView.busy} onClick={() => { void creation?.choose(op.operationId) }}>{op.operationId} — {op.state}</button></li>)}</ul>
      <button type="button" onClick={() => creation?.returnToStartup()}>I have inspected these operations; return to startup</button>
    </section>}
    {creation && creationView && status && !creationView.manualOpen && (newSession || creationView.root || creationView.blocked) ? <NewSessionPanel
      key={creationView.root?.operationId ?? 'new'} workflow={creation} view={creationView} status={status} onClose={() => setNewSession(false)} /> : browsing ? <FolderBrowser mode="open" onSelect={(path) => { void select(path) }} onCancel={() => setBrowsing(false)} busy={openActive} /> : <div className="home-actions">
      <article>
        <h2>New Session</h2>
        <p>Choose an existing parent folder and name a new Session.</p>
        <button type="button" disabled={active || !creation?.canStart()} onClick={() => { setError(null); setNewSession(true) }}>New Session</button>
      </article>
      <article>
        <h2>Open Existing Session</h2>
        <p>Continue with a Session you already have.</p>
        <button type="button" disabled={openActive} onClick={() => { setError(null); setBrowsing(true) }}>Open Existing Session</button>
      </article>
      <article><h2>Recent Sessions</h2><p>Recent Session history is coming in a later update.</p></article>
    </div>}
  </section>
}
