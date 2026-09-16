import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'

import { ApiError } from '../api/http.ts'
import {
  indexingStageLabels,
  indexingCompletionStatus,
  IndexingWorkflowError,
  runSourceIndexing,
  type IndexingProgress,
  type IndexingStage,
  type IndexingCompletionStatus,
} from '../api/indexing.ts'
import {
  getSources,
  registerSource,
  validateSourceRegistration,
  type RegisterSourceInput,
  type Source,
} from '../api/sources.ts'

const stageOrder: IndexingStage[] = [
  'PREPARING',
  'DISCOVERY',
  'RECONCILIATION',
  'CONTENT_ASSIGNMENT',
  'CONTENT_HASHING',
  'COMPLETE',
]

interface ActiveRun {
  sourceId: number
  sourceName: string
  status: 'active' | IndexingCompletionStatus | 'failed'
  progress: IndexingProgress
  failedStage: IndexingStage | null
  errorMessage: string | null
}

function emptyProgress(): IndexingProgress {
  return {
    stage: 'PREPARING',
    scanRunId: null,
    jobId: null,
    discoveredFileCount: null,
    reconciledSourceCount: null,
    assignment: null,
    hashing: null,
  }
}

function mergeSources(current: Source[], incoming: Source[]): Source[] {
  const byId = new Map(current.map((source) => [source.id, source]))
  incoming.forEach((source) => byId.set(source.id, source))
  return [...byId.values()].sort((left, right) => left.id - right.id)
}

function registrationErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 400) {
    return 'The backend did not accept this Source. Check the name and use an absolute path for the computer running the backend.'
  }
  return 'The Source could not be registered. Check that the backend is running and try again.'
}

function indexingErrorMessage(error: unknown): string {
  if (!(error instanceof IndexingWorkflowError)) {
    return 'Analysis stopped because the backend request could not be completed.'
  }

  if (
    error.stage === 'DISCOVERY' &&
    error.cause instanceof ApiError &&
    error.cause.status === 500
  ) {
    return 'File discovery could not read this Source. Confirm that its path is available to the local backend, then start a new analysis.'
  }

  if (error.cause instanceof ApiError) {
    if (error.cause.status === 404) {
      return 'Analysis stopped because its saved backend state could not be found.'
    }
    if (error.cause.status === 409) {
      return 'Analysis stopped because the backend state no longer allowed this stage.'
    }
  }

  return 'Analysis stopped because the backend could not complete this stage.'
}

function stageState(
  run: ActiveRun,
  candidate: IndexingStage,
): 'waiting' | 'running' | 'complete' | 'failed' {
  if (run.status === 'failed' && run.failedStage === candidate) return 'failed'
  if (run.status === 'complete' || run.status === 'complete-with-issues') {
    return 'complete'
  }

  const activeIndex = stageOrder.indexOf(run.progress.stage)
  const candidateIndex = stageOrder.indexOf(candidate)
  if (candidateIndex < activeIndex) return 'complete'
  if (candidateIndex === activeIndex) return 'running'
  return 'waiting'
}

function stageStateLabel(state: ReturnType<typeof stageState>): string {
  if (state === 'running') return 'In progress'
  if (state === 'complete') return 'Complete'
  if (state === 'failed') return 'Failed'
  return 'Waiting'
}

interface IndexingPanelProps {
  run: ActiveRun
  onStartNew: () => void
}

export function IndexingPanel({ run, onStartNew }: IndexingPanelProps) {
  const { progress } = run

  return (
    <section className="indexing-panel" aria-labelledby="indexing-heading">
      <div className="indexing-heading-row">
        <div>
          <p className="eyebrow">Source analysis</p>
          <h2 id="indexing-heading">{run.sourceName}</h2>
        </div>
        <span className={`run-status ${run.status}`}>
          {run.status === 'active'
            ? indexingStageLabels[progress.stage]
            : run.status === 'complete'
              ? 'Analysis complete'
              : run.status === 'complete-with-issues'
                ? 'Analysis finished with issues'
                : 'Analysis stopped'}
        </span>
      </div>

      <ol className="stage-list" aria-label="Analysis progress">
        {stageOrder.map((candidate) => {
          const state = stageState(run, candidate)
          return (
            <li className={state} key={candidate}>
              <span className="stage-marker" aria-hidden="true" />
              <span>{indexingStageLabels[candidate]}</span>
              <small>{stageStateLabel(state)}</small>
            </li>
          )
        })}
      </ol>

      {(progress.discoveredFileCount !== null ||
        progress.reconciledSourceCount !== null ||
        progress.assignment !== null ||
        progress.hashing !== null) && (
        <dl className="pipeline-metrics">
          {progress.discoveredFileCount !== null && (
            <div>
              <dt>Files discovered</dt>
              <dd>{progress.discoveredFileCount.toLocaleString()}</dd>
            </div>
          )}
          {progress.reconciledSourceCount !== null && (
            <div>
              <dt>Sources reconciled</dt>
              <dd>{progress.reconciledSourceCount.toLocaleString()}</dd>
            </div>
          )}
          {progress.assignment !== null && (
            <div>
              <dt>Content assigned</dt>
              <dd>{progress.assignment.assignedCount.toLocaleString()}</dd>
              {progress.assignment.skippedCount > 0 && (
                <small>
                  {progress.assignment.skippedCount.toLocaleString()} stale skipped
                </small>
              )}
            </div>
          )}
          {progress.hashing !== null && (
            <div>
              <dt>Hashes</dt>
              <dd>
                {progress.hashing.hashedCount.toLocaleString()} new ·{' '}
                {progress.hashing.cachedCount.toLocaleString()} reused
              </dd>
              {(progress.hashing.skippedCount > 0 ||
                progress.hashing.failedCount > 0) && (
                <small>
                  {progress.hashing.skippedCount.toLocaleString()} skipped ·{' '}
                  {progress.hashing.failedCount.toLocaleString()} failed
                </small>
              )}
            </div>
          )}
        </dl>
      )}

      <div
        className={`run-message ${run.status}`}
        role={run.status === 'failed' ? 'alert' : 'status'}
        aria-live="polite"
      >
        {run.status === 'active' && (
          <p>
            {indexingStageLabels[progress.stage]} is running in a synchronous
            backend request. Keep this page open until it finishes; refreshing
            cannot resume this in-browser sequence.
          </p>
        )}
        {run.status === 'complete' && (
          <div>
            <strong>Analysis complete</strong>
            <p>The catalog is ready for exact duplicate browsing.</p>
            <Link className="primary-link" to="/duplicates">
              View exact duplicates
            </Link>
          </div>
        )}
        {run.status === 'complete-with-issues' && (
          <div>
            <strong>Analysis finished with issues</strong>
            <p>
              The indexing pipeline finished, but some files were skipped or
              could not be hashed. Successfully analyzed content remains
              available for exact duplicate browsing.
            </p>
            <Link className="primary-link" to="/duplicates">
              View exact duplicates
            </Link>
          </div>
        )}
        {run.status === 'failed' && (
          <div>
            <strong>{indexingStageLabels[run.failedStage ?? progress.stage]} failed</strong>
            <p>{run.errorMessage}</p>
            <p>
              This attempt is not retried automatically. Starting again creates
              a new ScanRun.
            </p>
            <button type="button" onClick={onStartNew}>
              Start new analysis
            </button>
          </div>
        )}
      </div>

      {(progress.scanRunId !== null || progress.jobId !== null) && (
        <p className="run-identifiers">
          {progress.scanRunId !== null && `ScanRun #${progress.scanRunId}`}
          {progress.scanRunId !== null && progress.jobId !== null && ' · '}
          {progress.jobId !== null && `Job #${progress.jobId}`}
        </p>
      )}
    </section>
  )
}

export function SourcesPage() {
  const [sources, setSources] = useState<Source[] | null>(null)
  const [listError, setListError] = useState(false)
  const [listRequest, setListRequest] = useState(0)
  const [form, setForm] = useState<RegisterSourceInput>({
    name: '',
    rootPath: '',
  })
  const [formError, setFormError] = useState<string | null>(null)
  const [formSuccess, setFormSuccess] = useState<string | null>(null)
  const [isRegistering, setIsRegistering] = useState(false)
  const [run, setRun] = useState<ActiveRun | null>(null)
  const registrationLock = useRef(false)
  const analysisLock = useRef(false)
  const analysisController = useRef<AbortController | null>(null)

  useEffect(() => {
    const controller = new AbortController()

    getSources(controller.signal)
      .then((loadedSources) =>
        setSources((current) =>
          current === null
            ? loadedSources
            : mergeSources(current, loadedSources),
        ),
      )
      .catch((error: unknown) => {
        if (!(error instanceof DOMException && error.name === 'AbortError')) {
          setListError(true)
        }
      })

    return () => controller.abort()
  }, [listRequest])

  useEffect(
    () => () => {
      analysisController.current?.abort()
    },
    [],
  )

  async function submitSource(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (registrationLock.current) return

    const validationMessage = validateSourceRegistration(form)
    if (validationMessage) {
      setFormError(validationMessage)
      setFormSuccess(null)
      return
    }

    registrationLock.current = true
    setIsRegistering(true)
    setFormError(null)
    setFormSuccess(null)
    try {
      const created = await registerSource(form)
      setSources((current) =>
        mergeSources(current ?? [], [created]),
      )
      setForm({ name: '', rootPath: '' })
      setFormSuccess(`${created.name} was registered.`)
    } catch (error: unknown) {
      setFormError(registrationErrorMessage(error))
    } finally {
      registrationLock.current = false
      setIsRegistering(false)
    }
  }

  async function startAnalysis(source: Source) {
    if (analysisLock.current) return

    analysisLock.current = true
    const controller = new AbortController()
    analysisController.current = controller
    let latestProgress = emptyProgress()
    setRun({
      sourceId: source.id,
      sourceName: source.name,
      status: 'active',
      progress: latestProgress,
      failedStage: null,
      errorMessage: null,
    })

    try {
      const completed = await runSourceIndexing(
        source.id,
        (progress) => {
          latestProgress = progress
          setRun({
            sourceId: source.id,
            sourceName: source.name,
            status:
              progress.stage === 'COMPLETE'
                ? indexingCompletionStatus(progress)
                : 'active',
            progress,
            failedStage: null,
            errorMessage: null,
          })
        },
        { signal: controller.signal },
      )
      latestProgress = completed
    } catch (error: unknown) {
      if (!controller.signal.aborted) {
        const failedStage =
          error instanceof IndexingWorkflowError
            ? error.stage
            : latestProgress.stage
        setRun({
          sourceId: source.id,
          sourceName: source.name,
          status: 'failed',
          progress: latestProgress,
          failedStage,
          errorMessage: indexingErrorMessage(error),
        })
      }
    } finally {
      if (analysisController.current === controller) {
        analysisController.current = null
        analysisLock.current = false
      }
    }
  }

  const activeSourceId = run?.status === 'active' ? run.sourceId : null

  function retrySourceList() {
    setListError(false)
    setSources(null)
    setListRequest((value) => value + 1)
  }

  return (
    <div className="sources-page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">Catalog setup</p>
          <h1>Sources</h1>
          <p className="page-intro">
            Register a local folder, then analyze it through discovery,
            catalog reconciliation, content assignment, and exact hashing.
          </p>
        </div>
      </div>

      <section className="source-registration" aria-labelledby="register-source-heading">
        <div>
          <h2 id="register-source-heading">Register a Source</h2>
          <p className="section-intro">
            The path is interpreted by the local backend. It may be unavailable
            now and checked later when analysis begins.
          </p>
        </div>
        <form onSubmit={submitSource} noValidate>
          <div className="source-field">
            <label htmlFor="source-name">Name</label>
            <input
              id="source-name"
              name="name"
              type="text"
              value={form.name}
              disabled={isRegistering}
              onChange={(event) =>
                setForm((current) => ({ ...current, name: event.target.value }))
              }
            />
          </div>

          <div className="source-field path-field">
            <label htmlFor="source-root-path">Absolute root path</label>
            <input
              id="source-root-path"
              name="rootPath"
              type="text"
              value={form.rootPath}
              disabled={isRegistering}
              aria-describedby="source-path-help"
              onChange={(event) =>
                setForm((current) => ({
                  ...current,
                  rootPath: event.target.value,
                }))
              }
            />
            <p className="field-help" id="source-path-help">
              Examples: <code>/Users/chris/Pictures</code> or{' '}
              <code>C:\Users\Chris\Pictures</code>
            </p>
          </div>

          <button type="submit" disabled={isRegistering}>
            {isRegistering ? 'Registering…' : 'Register Source'}
          </button>
          {formError && (
            <p className="form-message error" role="alert">
              {formError}
            </p>
          )}
          {formSuccess && (
            <p className="form-message success" role="status">
              {formSuccess}
            </p>
          )}
        </form>
      </section>

      {run && (
        <IndexingPanel
          run={run}
          onStartNew={() => {
            const source = sources?.find((candidate) => candidate.id === run.sourceId)
            if (source) void startAnalysis(source)
          }}
        />
      )}

      <section className="source-list-section" aria-labelledby="registered-sources-heading">
        <div className="section-heading-row">
          <div>
            <h2 id="registered-sources-heading">Registered Sources</h2>
            <p>Analysis reads from these paths but does not modify their files.</p>
          </div>
          {sources !== null && (
            <span>{sources.length.toLocaleString()} registered</span>
          )}
        </div>

        {sources === null && !listError && (
          <p className="source-list-state" role="status">
            Loading Sources…
          </p>
        )}
        {listError && (
          <div className="source-list-state error" role="alert">
            <p>Sources could not be loaded. Check that the backend is running.</p>
            <button type="button" onClick={retrySourceList}>
              Try again
            </button>
          </div>
        )}
        {sources?.length === 0 && (
          <p className="source-list-state">No Sources are registered yet.</p>
        )}
        {sources && sources.length > 0 && (
          <div className="source-list">
            {sources.map((source) => (
              <article className="source-card" key={source.id}>
                <div className="source-card-main">
                  <div className="source-name-row">
                    <h3>{source.name}</h3>
                    <span>Source #{source.id}</span>
                  </div>
                  <p className="source-path" title={source.rootPath}>
                    {source.rootPath}
                  </p>
                </div>
                <button
                  type="button"
                  disabled={activeSourceId !== null}
                  onClick={() => void startAnalysis(source)}
                >
                  {activeSourceId === source.id ? 'Analyzing…' : 'Analyze Source'}
                </button>
              </article>
            ))}
          </div>
        )}
      </section>
    </div>
  )
}
