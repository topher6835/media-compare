import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from 'react'
import { Link } from 'react-router-dom'

import { ApiError } from '../api/http.ts'
import { SourceAuthorityControls, type AuthorityReleaseMessage } from './SourceAuthorityControls.tsx'
import { metadataHasIssues } from './metadataWorkflow.ts'
import { useMetadataWorkflow, type MetadataPhase } from './useMetadataWorkflow.ts'
import {
  clearPendingIndexingStart,
  getIndexingRun,
  getIndexingSourceStatus,
  getPendingIndexingStart,
  indexingStageLabels,
  isActiveIndexingRun,
  rememberPendingIndexingStart,
  startIndexingRun,
  type IndexingRun,
  type IndexingRunSummary,
  type IndexingSourceStatus,
  type IndexingStageType,
  type PendingIndexingStart,
} from '../api/indexing.ts'
import {
  canAnalyzeSource,
  sourceAuthorityWindows,
  releaseSourceAuthority,
  releaseAuthorityMessage,
  getSources,
  prepareSource,
  registerSource,
  SourcePreparationApiError,
  validateSourceRegistration,
  type RegisterSourceInput,
  type Source,
} from '../api/sources.ts'

const pollIntervalMs = 1500
const stageOrder: IndexingStageType[] = [
  'DISCOVERY',
  'RECONCILIATION',
  'CONTENT_ASSIGNMENT',
  'CONTENT_HASHING',
]

function registrationErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 400) {
    return 'The backend did not accept this Source. Check the name and use an absolute path for the computer running the backend.'
  }
  return 'The Source could not be registered. Check that the backend is running and try again.'
}

function preparationErrorMessage(error: unknown): string {
  if (error instanceof SourcePreparationApiError) {
    if (error.code === 'PATH_UNAVAILABLE') {
      return 'This folder is unavailable to the backend. Check the path and try again.'
    }
    if (error.code === 'PROFILE_UNSUPPORTED') {
      return 'This storage profile is unavailable. Production exFAT support is awaiting review; local APFS and NTFS are supported.'
    }
    if (error.code === 'EVIDENCE_UNCERTAIN') {
      return 'The filesystem could not be verified consistently. Try again when the folder is stable.'
    }
    if (error.status === 409 || error.code === 'STATE_CHANGED') {
      return 'Source or storage state changed. Refresh the Source list before trying again.'
    }
  }
  return 'The Source could not be prepared. Check that the backend is running and try again.'
}

function runDisplayStatus(run: IndexingRunSummary): string {
  if (run.status === 'COMPLETED') {
    return 'Indexing complete'
  }
  if (run.status === 'FAILED') return 'Analysis stopped'
  if (run.status === 'PENDING') return 'Waiting to start'
  if (run.currentStage) return indexingStageLabels[run.currentStage]
  return 'Running in background'
}

function isDefinitiveStartResponse(error: ApiError): boolean {
  return [400, 404, 409, 503].includes(error.status)
}

function runStatusClass(
  run: IndexingRunSummary,
): 'active' | 'complete' | 'complete-with-issues' | 'failed' {
  if (isActiveIndexingRun(run)) return 'active'
  if (run.status === 'FAILED') return 'failed'
  return run.completedWithIssues ? 'complete-with-issues' : 'complete'
}

function analysisStatus(run: IndexingRunSummary, metadata: MetadataPhase | null): string {
  if (run.status !== 'COMPLETED' || metadata?.scanRunId !== run.scanRunId) return runDisplayStatus(run)
  if (metadata.state === 'complete' && metadata.run) {
    return run.completedWithIssues || metadataHasIssues(metadata.run)
      ? 'Analysis finished with issues' : 'Analysis complete'
  }
  if (metadata.state === 'failed') return 'Metadata analysis failed'
  if (metadata.state === 'running') return 'Analyzing image metadata'
  return 'Checking metadata analysis'
}

function stageState(
  run: IndexingRun,
  candidate: IndexingStageType,
): 'waiting' | 'pending' | 'running' | 'complete' | 'failed' {
  const stage = run.stages.find((item) => item.stageType === candidate)
  if (!stage) return 'waiting'
  if (stage.status === 'FAILED') return 'failed'
  if (stage.status === 'COMPLETED') return 'complete'
  if (stage.status === 'RUNNING') return 'running'
  return run.currentStage === candidate ? 'pending' : 'waiting'
}

function stageStateLabel(state: ReturnType<typeof stageState>): string {
  if (state === 'pending') return 'Pending'
  if (state === 'running') return 'In progress'
  if (state === 'complete') return 'Complete'
  if (state === 'failed') return 'Failed'
  return 'Waiting'
}

interface IndexingPanelProps {
  run: IndexingRun
  sourceName: string
  canStartNew: boolean
  onStartNew: () => void
  metadata: MetadataPhase | null
  onRetryMetadata: () => void
}

function IndexingPanel({
  run,
  sourceName,
  canStartNew,
  onStartNew,
  metadata,
  onRetryMetadata,
}: IndexingPanelProps) {
  const metadataDone = metadata?.state === 'complete' && metadata.run !== null
  const metadataFailed = metadata?.state === 'failed'
  const completedWithIssues = run.completedWithIssues || (metadataDone && metadataHasIssues(metadata.run!))
  const statusClass = run.status === 'COMPLETED'
    ? metadataFailed ? 'failed' : metadataDone
      ? completedWithIssues ? 'complete-with-issues' : 'complete' : 'active'
    : runStatusClass(run)
  const discovery = run.stages.find((stage) => stage.stageType === 'DISCOVERY')
  const reconciliation = run.stages.find(
    (stage) => stage.stageType === 'RECONCILIATION',
  )

  return (
    <section className="indexing-panel" aria-labelledby="indexing-heading">
      <div className="indexing-heading-row">
        <div>
          <p className="eyebrow">Source analysis</p>
          <h2 id="indexing-heading">{sourceName}</h2>
        </div>
        <span className={`run-status ${statusClass}`}>
          {analysisStatus(run, metadata)}
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
        <li className={run.status !== 'COMPLETED' ? 'waiting' : metadataFailed ? 'failed'
          : metadataDone ? 'complete' : 'running'}>
          <span className="stage-marker" aria-hidden="true" />
          <span>Analyzing image metadata</span>
          <small>{run.status !== 'COMPLETED' ? 'Waiting' : metadataFailed ? 'Failed'
            : metadataDone ? 'Complete' : metadata?.state === 'running' ? 'In progress' : 'Checking'}</small>
        </li>
      </ol>

      {(discovery !== undefined ||
        reconciliation !== undefined ||
        run.assignmentResult !== null ||
        run.hashingResult !== null) && (
        <dl className="pipeline-metrics">
          {discovery && (
            <div>
              <dt>Files discovered</dt>
              <dd>
                {discovery.progressCompleted.toLocaleString()}
                {discovery.progressTotal !== null &&
                  ` of ${discovery.progressTotal.toLocaleString()}`}
              </dd>
            </div>
          )}
          {reconciliation && (
            <div>
              <dt>Sources reconciled</dt>
              <dd>
                {reconciliation.progressCompleted.toLocaleString()}
                {reconciliation.progressTotal !== null &&
                  ` of ${reconciliation.progressTotal.toLocaleString()}`}
              </dd>
            </div>
          )}
          {run.assignmentResult !== null && (
            <div>
              <dt>Content assigned</dt>
              <dd>{run.assignmentResult.assignedCount.toLocaleString()}</dd>
              {run.assignmentResult.skippedCount > 0 && (
                <small>
                  {run.assignmentResult.skippedCount.toLocaleString()} stale skipped
                </small>
              )}
            </div>
          )}
          {run.hashingResult !== null && (
            <div>
              <dt>Hashes</dt>
              <dd>
                {run.hashingResult.hashedCount.toLocaleString()} new ·{' '}
                {run.hashingResult.cachedCount.toLocaleString()} reused
              </dd>
              {(run.hashingResult.skippedCount > 0 ||
                run.hashingResult.failedCount > 0) && (
                <small>
                  {run.hashingResult.skippedCount.toLocaleString()} skipped ·{' '}
                  {run.hashingResult.failedCount.toLocaleString()} failed
                </small>
              )}
            </div>
          )}
        </dl>
      )}

      <div
        className={`run-message ${statusClass}`}
        role={run.status === 'FAILED' || metadataFailed ? 'alert' : 'status'}
        aria-live="polite"
      >
        {run.status === 'PENDING' && (
          <p>Analysis is waiting for the background worker.</p>
        )}
        {run.status === 'RUNNING' && (
          <p>
            {run.currentStage
              ? `${indexingStageLabels[run.currentStage]} is running in the background. You can leave this page and return without interrupting it.`
              : 'Analysis is waiting for the background worker.'}
          </p>
        )}
        {run.status === 'COMPLETED' && !metadataDone && !metadataFailed && (
          <p>{metadata?.state === 'unavailable'
            ? 'Indexing is complete. Metadata status is unavailable. For exFAT, explicitly Prepare/Accept on Sources before a new metadata attempt.'
            : 'Indexing is complete. Image metadata is being checked or analyzed before Library is ready.'}</p>
        )}
        {run.status === 'COMPLETED' && metadataFailed && (
          <div>
            <strong>Metadata analysis failed</strong>
            <p>Indexing is complete. Image metadata could not finish; cataloged images remain visible in Library. For exFAT, Prepare/Accept before retrying as a new standalone metadata attempt.</p>
            {metadata?.run?.errorMessage && <p>{metadata.run.errorMessage}</p>}
            <button type="button" onClick={onRetryMetadata}>Retry metadata analysis</button>
          </div>
        )}
        {run.status === 'COMPLETED' && metadataDone && !completedWithIssues && (
          <div>
            <strong>Analysis complete</strong>
            <p>Library is ready.</p>
            {metadata.run!.stage.result?.completedUnsupported
              ? <p>Some files use formats without previews yet.</p> : null}
            <Link className="primary-link" to="/library">View Library</Link>{' '}
            <Link className="primary-link" to="/duplicates">
              View exact duplicates
            </Link>
          </div>
        )}
        {run.status === 'COMPLETED' && metadataDone && completedWithIssues && (
          <div>
            <strong>Analysis finished with issues</strong>
            <p>
              Indexing and image metadata finished with skipped, stale, or failed files.
              Cataloged images remain visible in Library.
            </p>
            {metadata.run!.stage.result && <p>
              Metadata: {metadata.run!.stage.result.failed} failed ·{' '}
              {metadata.run!.stage.result.staleOrUnavailable} stale or unavailable ·{' '}
              {metadata.run!.stage.result.completedUnsupported} unsupported formats.
            </p>}
            <Link className="primary-link" to="/library">View Library</Link>{' '}
            <Link className="primary-link" to="/duplicates">
              View exact duplicates
            </Link>
          </div>
        )}
        {run.status === 'FAILED' && (
          <div>
            <strong>Analysis stopped</strong>
            <p>{run.errorMessage ?? 'The backend could not complete this analysis.'}</p>
            <p>A new attempt creates a new durable indexing run.</p>
            <button type="button" disabled={!canStartNew} onClick={onStartNew}>
              Start new analysis
            </button>
          </div>
        )}
      </div>

      <p className="run-identifiers">
        ScanRun #{run.scanRunId} · Indexing Job #{run.jobId}
        {metadata?.run && ` · Metadata Job #${metadata.run.jobId}`}
      </p>
    </section>
  )
}

export function SourcesPage() {
  const [sources, setSources] = useState<Source[] | null>(null)
  const [sourceStatus, setSourceStatus] =
    useState<IndexingSourceStatus | null>(null)
  const [listError, setListError] = useState(false)
  const [listRequest, setListRequest] = useState(0)
  const [form, setForm] = useState<RegisterSourceInput>({
    name: '',
    rootPath: '',
  })
  const [formError, setFormError] = useState<string | null>(null)
  const [formSuccess, setFormSuccess] = useState<string | null>(null)
  const [isRegistering, setIsRegistering] = useState(false)
  const [preparingSourceId, setPreparingSourceId] = useState<number | null>(null)
  const [preparationMessage, setPreparationMessage] = useState<{
    sourceId: number
    text: string
  } | null>(null)
  const [run, setRun] = useState<IndexingRun | null>(null)
  const [startingSourceId, setStartingSourceId] = useState<number | null>(null)
  const [uncertainStart, setUncertainStart] = useState<PendingIndexingStart | null>(
    getPendingIndexingStart,
  )
  const [statusRecoveryNeeded, setStatusRecoveryNeeded] = useState(false)
  const [statusRecoveryRequest, setStatusRecoveryRequest] = useState(0)
  const [startMessage, setStartMessage] = useState<string | null>(null)
  const [startMessageIsError, setStartMessageIsError] = useState(false)
  const registrationLock = useRef(false)
  const preparationLock = useRef(false)
  const startLock = useRef(false)
  const mounted = useRef(true)
  const recoveryMessage = useRef(false)

  const showStartMessage = useCallback(
    (message: string, isError: boolean, isTransientRecovery = false) => {
      recoveryMessage.current = isTransientRecovery
      setStartMessage(message)
      setStartMessageIsError(isError)
    },
    [],
  )

  const clearTransientRecoveryMessage = useCallback(() => {
    if (!recoveryMessage.current) return
    recoveryMessage.current = false
    setStartMessage(null)
    setStartMessageIsError(false)
  }, [])

  const refreshCollection = useCallback(async (signal?: AbortSignal) => {
    const [loadedSources, loadedStatus] = await Promise.all([
      getSources(signal),
      getIndexingSourceStatus(signal),
    ])
    setSources(loadedSources)
    setSourceStatus(loadedStatus)
    setListError(false)
    return loadedStatus
  }, [])

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    Promise.all([
      getSources(controller.signal),
      getIndexingSourceStatus(controller.signal),
    ])
      .then(([loadedSources, loadedStatus]) => {
        setSources(loadedSources)
        setSourceStatus(loadedStatus)
        setListError(false)
      })
      .catch((error: unknown) => {
        if (!(error instanceof DOMException && error.name === 'AbortError')) {
          setListError(true)
        }
      })
    return () => controller.abort()
  }, [listRequest])

  useEffect(() => {
    if (!statusRecoveryNeeded) return

    const controller = new AbortController()
    let timer: number | undefined

    async function recoverCollection() {
      try {
        await refreshCollection(controller.signal)
        setStatusRecoveryNeeded(false)
        clearTransientRecoveryMessage()
      } catch {
        if (controller.signal.aborted) return
        setListError(true)
        showStartMessage(
          'Durable Source status could not be refreshed. Retrying status refresh.',
          true,
          true,
        )
        timer = window.setTimeout(recoverCollection, pollIntervalMs)
      }
    }

    void recoverCollection()
    return () => {
      controller.abort()
      if (timer !== undefined) window.clearTimeout(timer)
    }
  }, [
    clearTransientRecoveryMessage,
    refreshCollection,
    showStartMessage,
    statusRecoveryNeeded,
    statusRecoveryRequest,
  ])

  const effectiveActiveRun =
    sourceStatus?.active ?? (run && isActiveIndexingRun(run) ? run : null)
  const activeScanRunId = effectiveActiveRun?.scanRunId ?? null
  const latestSummary = [run, ...(sourceStatus?.sources.map((item) => item.latest) ?? [])]
    .filter((item): item is IndexingRunSummary => item !== null)
    .reduce<IndexingRunSummary | null>((latest, item) =>
      latest === null || item.scanRunId > latest.scanRunId ? item : latest, null)
  const completedSummary = latestSummary?.status === 'COMPLETED' ? latestSummary : null
  const completedScanRunId = completedSummary?.scanRunId ?? null
  const completedFinishedAtMs = completedSummary?.finishedAtMs ?? null
  const { phase: metadataPhase, retryMetadata } = useMetadataWorkflow(
    run?.scanRunId === completedScanRunId && sources !== null
      && run.sourceIds.every((id) => sources.some((source) => source.id === id))
      ? completedScanRunId : null, completedFinishedAtMs,
    run?.scanRunId === completedScanRunId
      ? sources?.find((source) => run.sourceIds.includes(source.id) && source.filesystemProfile === 'EXFAT')?.id ?? null
      : null,
  )

  useEffect(() => {
    if (completedScanRunId === null || run?.scanRunId === completedScanRunId) return
    const controller = new AbortController()
    getIndexingRun(completedScanRunId, controller.signal)
      .then(setRun)
      .catch(() => { /* The durable Source summary remains available for recovery. */ })
    return () => controller.abort()
  }, [completedScanRunId, run?.scanRunId])

  useEffect(() => {
    if (activeScanRunId === null) return

    const controller = new AbortController()
    let timer: number | undefined

    async function poll() {
      try {
        const current = await getIndexingRun(activeScanRunId!, controller.signal)
        const retainedAttempt = getPendingIndexingStart()
        if (retainedAttempt?.requestKey === current.requestKey) {
          clearPendingIndexingStart(current.requestKey)
          setUncertainStart(null)
          recoveryMessage.current = false
          setStartMessage(null)
        }
        setRun(current)
        await refreshCollection(controller.signal)
        clearTransientRecoveryMessage()
        if (isActiveIndexingRun(current)) {
          timer = window.setTimeout(poll, pollIntervalMs)
        } else {
          await refreshCollection(controller.signal)
            .then(clearTransientRecoveryMessage)
            .catch(() => {
              if (controller.signal.aborted) return
              setSourceStatus(null)
              setStatusRecoveryNeeded(true)
              setListError(true)
              showStartMessage(
                'Analysis finished, but the latest Source status could not be refreshed.',
                true,
                true,
              )
            })
        }
      } catch (error: unknown) {
        if (controller.signal.aborted) return
        if (error instanceof ApiError && error.status === 404) {
          setRun(null)
          setSourceStatus(null)
          setStatusRecoveryNeeded(true)
          showStartMessage(
            'The active indexing run is no longer available. Refreshing durable Source status.',
            true,
            true,
          )
          return
        }
        showStartMessage(
          'Current indexing progress could not be refreshed. Polling will continue.',
          true,
          true,
        )
        timer = window.setTimeout(poll, pollIntervalMs)
      }
    }

    void poll()
    return () => {
      controller.abort()
      if (timer !== undefined) window.clearTimeout(timer)
    }
  }, [activeScanRunId, clearTransientRecoveryMessage, refreshCollection, showStartMessage])

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
      setForm({ name: '', rootPath: '' })
      setFormSuccess(`${created.name} was registered.`)
      await refreshCollection().catch(() => setListError(true))
    } catch (error: unknown) {
      setFormError(registrationErrorMessage(error))
    } finally {
      registrationLock.current = false
      setIsRegistering(false)
    }
  }

  async function startAnalysis(source: Source, retryUncertain = false) {
    if (startLock.current || (!retryUncertain && !canAnalyzeSource(source))) return

    const retainedAttempt = getPendingIndexingStart()
    const requestKey =
      retryUncertain && retainedAttempt?.sourceId === source.id
        ? retainedAttempt.requestKey
        : crypto.randomUUID()
    const attempt: PendingIndexingStart = { sourceId: source.id, requestKey,
      authorityWindows: retryUncertain ? retainedAttempt?.authorityWindows : undefined }
    rememberPendingIndexingStart(attempt)
    startLock.current = true
    setStartingSourceId(source.id)
    setUncertainStart(null)
    recoveryMessage.current = false
    setStartMessage(null)

    try {
      if (!retryUncertain) {
        const fresh = (await getSources()).find((item) => item.id === source.id)
        if (!fresh || !canAnalyzeSource(fresh)) {
          clearPendingIndexingStart(requestKey)
          await refreshCollection()
          showStartMessage('Prepare/Accept this Source before analysis.', true)
          return
        }
        attempt.authorityWindows = sourceAuthorityWindows(fresh)
        rememberPendingIndexingStart(attempt)
      }
      const accepted = await startIndexingRun(requestKey, source.id, attempt.authorityWindows)
      clearPendingIndexingStart(requestKey)
      if (!mounted.current) return
      setRun(accepted)
      setUncertainStart(null)
      await refreshCollection().catch(() => setListError(true))
    } catch (error: unknown) {
      if (error instanceof ApiError && isDefinitiveStartResponse(error)) {
        clearPendingIndexingStart(requestKey)
        if (!mounted.current) return
        setUncertainStart(null)
        if (error.status === 409) {
          showStartMessage(
            'Indexing state changed in another request. The durable Source status has been refreshed.',
            false,
            true,
          )
          try {
            await refreshCollection()
          } catch {
            setListError(true)
          }
        } else if (error.status === 503) {
          showStartMessage(
            'The indexing attempt could not be scheduled. Its durable failed result is shown below; starting again will create a new attempt.',
            true,
          )
          try {
            await refreshCollection()
          } catch {
            setListError(true)
          }
        } else {
          showStartMessage(
            'The backend did not accept the indexing request. Check that it is running and try again.',
            true,
          )
        }
      } else {
        if (!mounted.current) return
        setUncertainStart(attempt)
        showStartMessage(
          'The request outcome is uncertain. Retry this request to reuse the same request key safely.',
          true,
        )
        try {
          await refreshCollection()
        } catch {
          setListError(true)
        }
      }
    } finally {
      startLock.current = false
      if (mounted.current) setStartingSourceId(null)
    }
  }

  async function prepare(source: Source) {
    if (preparationLock.current || source.preparationState === 'REBIND_REQUIRED') return
    preparationLock.current = true
    setPreparingSourceId(source.id)
    setPreparationMessage(null)
    try {
      const ready = await prepareSource(source.id)
      if (!mounted.current) return
      setSources((current) => current?.map((item) => item.id === ready.id ? ready : item) ?? null)
      await refreshCollection().catch(() => setListError(true))
    } catch (error: unknown) {
      if (!mounted.current) return
      setPreparationMessage({ sourceId: source.id, text: preparationErrorMessage(error) })
      if (error instanceof SourcePreparationApiError && error.status === 409) {
        await refreshCollection().catch(() => setListError(true))
      }
    } finally {
      preparationLock.current = false
      if (mounted.current) setPreparingSourceId(null)
    }
  }

  const [releasePending, setReleasePending] = useState<number | null>(null)
  const [releaseMessages, setReleaseMessages] = useState<Record<number, AuthorityReleaseMessage>>({})

  async function release(source: Source, windowId = source.liveAuthorityWindowId) {
    if (!windowId || releasePending !== null) return
    setReleasePending(source.id)
    try {
      const result = await releaseSourceAuthority(source.id, windowId)
      setReleaseMessages((current) => ({ ...current, [source.id]: {
        windowId, text: releaseAuthorityMessage(result), draining: result.releaseState === 'DRAINING',
      } }))
    } catch {
      setReleaseMessages((current) => ({ ...current, [source.id]: {
        windowId, text: 'Release status unavailable. Check this exact authority again.', draining: true,
      } }))
    } finally {
      await refreshCollection().catch(() => setListError(true))
      setReleasePending(null)
    }
  }

  useEffect(() => {
    const refresh = () => { void refreshCollection().catch(() => setListError(true)) }
    window.addEventListener('focus', refresh)
    return () => window.removeEventListener('focus', refresh)
  }, [refreshCollection])

  useEffect(() => {
    if (metadataPhase?.state !== 'running' && metadataPhase?.state !== 'complete'
      && metadataPhase?.state !== 'failed') return
    const timer = window.setTimeout(() => { void refreshCollection().catch(() => setListError(true)) }, 0)
    return () => window.clearTimeout(timer)
  }, [metadataPhase, refreshCollection])

  const metadataById = new Map(sources?.map((source) => [source.id, source]))
  const displayedSources =
    sourceStatus?.sources.map((status) => ({
      status,
      source: metadataById.get(status.sourceId),
    })) ?? []
  const effectiveActiveSourceIds =
    effectiveActiveRun?.scanRunId === run?.scanRunId ? (run?.sourceIds ?? []) : []
  const globallyBusy =
    effectiveActiveRun !== null ||
    (metadataPhase !== null && ['checking', 'running', 'unavailable'].includes(metadataPhase.state)) ||
    preparingSourceId !== null ||
    startingSourceId !== null ||
    uncertainStart !== null ||
    statusRecoveryNeeded
  const panelSourceId = run?.sourceIds[0] ?? null
  const panelSource = panelSourceId === null ? null : metadataById.get(panelSourceId)
  const panelSourceName =
    panelSource?.name ??
    (panelSourceId === null ? 'Source' : `Source #${panelSourceId}`)

  function retrySourceList() {
    setListError(false)
    if (statusRecoveryNeeded) {
      setStatusRecoveryRequest((value) => value + 1)
      return
    }
    setListRequest((value) => value + 1)
  }

  return (
    <div className="sources-page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">Catalog setup</p>
          <h1>Sources</h1>
          <p className="page-intro">
            Register and prepare a local folder, then analyze it through discovery,
            catalog reconciliation, content assignment, exact hashing, and image metadata.
          </p>
        </div>
      </div>

      <section className="source-registration" aria-labelledby="register-source-heading">
        <div>
          <h2 id="register-source-heading">Register a Source</h2>
          <p className="section-intro">
            The path is interpreted by the local backend. It may be unavailable
            now and is checked when you prepare the Source.
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

      {startMessage && (
        <p
          className={`indexing-request-message${startMessageIsError ? ' error' : ''}`}
          role={startMessageIsError ? 'alert' : 'status'}
        >
          {startMessage}
        </p>
      )}

      {run && latestSummary?.scanRunId === run.scanRunId && (
        <IndexingPanel
          run={run}
          sourceName={panelSourceName}
          canStartNew={!globallyBusy && !!panelSource && canAnalyzeSource(panelSource)}
          metadata={metadataPhase?.scanRunId === run.scanRunId ? metadataPhase : null}
          onRetryMetadata={retryMetadata}
          onStartNew={() => {
            const source =
              panelSourceId === null ? undefined : metadataById.get(panelSourceId)
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
          {sourceStatus !== null && (
            <span>{sourceStatus.sources.length.toLocaleString()} registered</span>
          )}
        </div>

        {(sources === null || sourceStatus === null) && !listError && (
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
        {sourceStatus?.sources.length === 0 && (
          <p className="source-list-state">No Sources are registered yet.</p>
        )}
        {displayedSources.length > 0 && (
          <div className="source-list">
            {displayedSources.map(({ source, status }) => {
              const latest = status.latest
              const freshActiveDetail =
                run !== null &&
                run.scanRunId === effectiveActiveRun?.scanRunId &&
                run.sourceIds.includes(status.sourceId)
                  ? run
                  : null
              const displayRun = freshActiveDetail ?? latest
              const isActiveSource =
                effectiveActiveRun !== null &&
                (displayRun?.scanRunId === effectiveActiveRun.scanRunId ||
                  effectiveActiveSourceIds.includes(status.sourceId))
              const activeSourceRun =
                displayRun?.scanRunId === effectiveActiveRun?.scanRunId
                  ? displayRun
                  : effectiveActiveRun
              const isStarting = startingSourceId === status.sourceId
              const isPreparing = preparingSourceId === status.sourceId
              const isUncertain = uncertainStart?.sourceId === status.sourceId
              const blockedByActiveRun =
                effectiveActiveRun !== null && !isActiveSource
              const blockedByLocalStart =
                effectiveActiveRun === null &&
                startingSourceId !== null &&
                !isStarting
              const blockedByUncertainStart =
                effectiveActiveRun === null &&
                startingSourceId === null &&
                uncertainStart !== null &&
                !isUncertain
              const disabledByOther =
                blockedByActiveRun ||
                blockedByLocalStart ||
                blockedByUncertainStart || (globallyBusy && !isUncertain)
              return (
                <article className="source-card" key={status.sourceId}>
                  <div className="source-card-main">
                    <div className="source-name-row">
                      <h3>{source?.name ?? `Source #${status.sourceId}`}</h3>
                      <span>Source #{status.sourceId}</span>
                    </div>
                    {source && (
                      <p className="source-path" title={source.rootPath}>
                        {source.rootPath}
                      </p>
                    )}
                    {source?.preparationState === 'READY' && (
                      <p className="source-action-note">{source.filesystemProfile === 'EXFAT'
                        ? `READY configuration · ${source.liveAuthorityAvailable ? 'Live authority available' : 'Prepare/Accept required'}` : 'Ready'}</p>
                    )}
                    {source?.preparationState === 'PREPARATION_REQUIRED' && (
                      <p className="source-action-note">Setup required</p>
                    )}
                    {source?.preparationState === 'REBIND_REQUIRED' && (
                      <p className="source-action-note">
                        Rebinding required. This Source was previously connected and must be reconnected before analysis.
                      </p>
                    )}
                    {preparationMessage?.sourceId === status.sourceId && (
                      <p className="source-action-note error" role="alert">
                        {preparationMessage.text}
                      </p>
                    )}
                    {displayRun && (
                      <div className={`source-latest ${runStatusClass(displayRun)}`}>
                        <strong>Latest: {analysisStatus(displayRun,
                          metadataPhase?.scanRunId === displayRun.scanRunId ? metadataPhase : null)}</strong>
                        {displayRun.status === 'FAILED' && displayRun.errorMessage && (
                          <span>{displayRun.errorMessage}</span>
                        )}
                        {displayRun.status === 'COMPLETED' && (
                          <Link to="/library">View Library</Link>
                        )}
                      </div>
                    )}
                    {blockedByActiveRun && (
                      <p className="source-action-note">
                        Another Source is currently indexing.
                      </p>
                    )}
                    {blockedByLocalStart && (
                      <p className="source-action-note">
                        Another Source is starting analysis.
                      </p>
                    )}
                    {blockedByUncertainStart && (
                      <p className="source-action-note">
                        Another indexing request is awaiting confirmation.
                      </p>
                    )}
                  </div>
                  {isActiveSource ? (
                    <span className="source-active-label" role="status">
                      {activeSourceRun
                        ? runDisplayStatus(activeSourceRun)
                        : 'Waiting to start'}
                    </span>
                  ) : source && (source.preparationState === 'PREPARATION_REQUIRED'
                    || source.preparationState === 'READY' && !canAnalyzeSource(source) && !isUncertain) ? (
                    <button
                      type="button"
                      disabled={preparingSourceId !== null || globallyBusy}
                      onClick={() => void prepare(source)}
                    >
                      {isPreparing ? 'Preparing…' : source.filesystemProfile === 'EXFAT' ? 'Prepare/Accept' : 'Prepare Source'}
                    </button>
                  ) : source?.preparationState === 'READY' ? (
                    <button
                      type="button"
                      disabled={disabledByOther || isStarting || preparingSourceId !== null}
                      onClick={() => {
                        void startAnalysis(source, isUncertain)
                      }}
                    >
                      {isStarting
                        ? 'Starting…'
                        : isUncertain
                          ? 'Retry request'
                          : 'Analyze Source'}
                    </button>
                  ) : (
                    <span className="source-active-label">Analysis unavailable</span>
                  )}
                  {source && <SourceAuthorityControls source={source} pending={releasePending !== null}
                    message={releaseMessages[source.id]} onRelease={(windowId) => void release(source, windowId)} />}
                </article>
              )
            })}
          </div>
        )}
      </section>
    </div>
  )
}
