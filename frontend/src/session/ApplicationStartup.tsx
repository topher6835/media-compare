import { useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'

import { getStartupMode, type StartupMode } from '../api/startup.ts'
import { SessionStartupPage } from './SessionStartupPage.tsx'

/** Keeps catalog routes unmounted until the responding service is identified. */
export function ApplicationStartup({ children }: { children: ReactNode }) {
  const [mode, setMode] = useState<StartupMode | 'loading' | 'unavailable'>('loading')
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    getStartupMode(controller.signal)
      .then((nextMode) => { if (!controller.signal.aborted) setMode(nextMode) })
      .catch(() => { if (!controller.signal.aborted) setMode('unavailable') })
    return () => controller.abort()
  }, [attempt])

  if (mode === 'catalog') return children

  return (
    <div className="app-shell">
      <header className="app-header">
        <Link className="brand" to="/">Media Compare</Link>
      </header>
      <main>
        {mode === 'management' ? <SessionStartupPage /> : (
          <section className="home-page">
            <h1>Media Compare</h1>
            <p className="page-intro" role="status">
              {mode === 'loading' ? 'Connecting to Media Compare…' :
                'Media Compare is unavailable. Check that the local service is running, then try again.'}
            </p>
            {mode === 'unavailable' && (
              <button type="button" onClick={() => {
                setMode('loading')
                setAttempt((value) => value + 1)
              }}>Try again</button>
            )}
          </section>
        )}
      </main>
    </div>
  )
}
