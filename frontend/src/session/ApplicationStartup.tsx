import { useEffect, useState, useSyncExternalStore, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { attachCatalog, currentCatalogId } from '../api/connection.ts'
import { getManagementStatus, getStartupMode, type ManagementStatus, type StartupMode } from '../api/startup.ts'
import { CreationWorkflow } from './creationWorkflow.ts'
import { SessionStartupPage } from './SessionStartupPage.tsx'

/** Only a ready, generation-pinned catalog can mount catalog routes. */
export function ApplicationStartup({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const [creation] = useState(() => new CreationWorkflow())
  const creationView = useSyncExternalStore(creation.subscribe, creation.getSnapshot, creation.getSnapshot)
  const [mode, setMode] = useState<StartupMode | 'loading' | 'unavailable'>('loading')
  const [status, setStatus] = useState<ManagementStatus | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [createdSession, setCreatedSession] = useState<{ name: string; path: string } | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    getStartupMode(controller.signal)
      .then((nextMode) => { if (!controller.signal.aborted) setMode(nextMode) })
      .catch(() => { if (!controller.signal.aborted) setMode('unavailable') })
    return () => controller.abort()
  }, [attempt])

  useEffect(() => {
    if (mode !== 'management') return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    async function poll() {
      try {
        const next = await getManagementStatus(controller.signal)
        if (controller.signal.aborted) return
        const attached = currentCatalogId()
        if (attached !== null && (attached !== next.catalogId || next.catalogState !== 'RUNNING')) {
          throw new Error('The selected catalog stopped or changed. Reload before opening a Session again.')
        }
        await creation.refresh(next, controller.signal)
        if (controller.signal.aborted) return
        if (next.catalogState === 'RUNNING' && next.catalogId !== null) {
          if (creation.ready(next)) {
            attachCatalog(next.catalogId)
            const result = creation.getSnapshot().attempt!
            setCreatedSession({ name: result.name!, path: result.path! })
            navigate('/sources', { replace: true })
            creation.completed()
          } else if (!creation.holdsStartup() || creation.manualCatalogReady(next)) attachCatalog(next.catalogId)
        }
        setStatus(next)
        setError(null)
      } catch (failure) {
        if (controller.signal.aborted) return
        setError(failure instanceof Error ? failure.message : 'Management is unavailable.')
      }
      if (!controller.signal.aborted) timer = setTimeout(() => { void poll() }, 1000)
    }
    void poll()
    return () => { controller.abort(); clearTimeout(timer) }
  }, [mode, creation, navigate])

  if (mode === 'catalog') return children
  if (mode === 'management' && status?.catalogState === 'RUNNING' && status.processAlive && status.catalogId === currentCatalogId() && (!creation.holdsStartup() || creation.manualCatalogReady(status)) && !error) return <>
    {createdSession && <aside className="creation-complete" role="status">
      <p>Session created: <strong>{createdSession.name}</strong><br /><span className="folder-current-path">{createdSession.path}</span></p>
      <button type="button" onClick={() => setCreatedSession(null)}>Dismiss creation message</button>
    </aside>}
    {children}
  </>

  return <div className="app-shell">
    <header className="app-header"><Link className="brand" to="/">Media Compare</Link></header>
    <main>
      {mode === 'management' && status ? <><SessionStartupPage status={status} managementAvailable={!error} onStatus={(next) => { creation.observeManualOpen(next); setStatus(next) }} creation={creation} creationView={creationView} />{error && <p role="alert">{error} Connection checks continue; work may still be running.</p>}</> : <section className="home-page">
        <h1>Media Compare</h1>
        <p className="page-intro" role="status">{error ?? (mode === 'loading' || mode === 'management' ? 'Connecting to Media Compare…' :
          'Media Compare is unavailable. Check that the local service is running, then try again.')}</p>
        {mode === 'unavailable' && <button type="button" onClick={() => { setMode('loading'); setAttempt((value) => value + 1) }}>Try again</button>}
        {error && <><p>Connection checks continue automatically. Reload to reconnect after a service restart or Session change.</p><button type="button" onClick={() => window.location.reload()}>Reload</button></>}
      </section>}
    </main>
  </div>
}
