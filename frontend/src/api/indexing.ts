import { requestJson } from './http.ts'

export interface ScanRunSource {
  sourceId: number
  status: string
  sourceLocationRevision: number
  traversalGeneration: number
  completedGeneration: number | null
}

export interface ScanRun {
  id: number
  requestType: string
  status: string
  createdAtMs: number
  sources: ScanRunSource[]
}

export interface JobStage {
  stageType: string
  status: string
  progressCompleted: number
  progressTotal: number | null
  attemptCount: number
}

export interface ScanExecution {
  jobId: number
  scanRunId: number
  jobType: string
  status: string
  currentStageType: string | null
  progressCompleted: number
  progressTotal: number | null
  attemptCount: number
  createdAtMs: number
  stages: JobStage[]
}

export interface ContentAssignmentResult {
  scanRunId: number
  assignedCount: number
  skippedCount: number
}

export interface ContentHashingResult {
  scanRunId: number
  hashedCount: number
  cachedCount: number
  skippedCount: number
  failedCount: number
}

export type IndexingStage =
  | 'PREPARING'
  | 'DISCOVERY'
  | 'RECONCILIATION'
  | 'CONTENT_ASSIGNMENT'
  | 'CONTENT_HASHING'
  | 'COMPLETE'

export const indexingStageLabels: Record<IndexingStage, string> = {
  PREPARING: 'Preparing',
  DISCOVERY: 'Discovering files',
  RECONCILIATION: 'Reconciling catalog',
  CONTENT_ASSIGNMENT: 'Assigning content',
  CONTENT_HASHING: 'Hashing content',
  COMPLETE: 'Complete',
}

export interface IndexingProgress {
  stage: IndexingStage
  scanRunId: number | null
  jobId: number | null
  discoveredFileCount: number | null
  reconciledSourceCount: number | null
  assignment: ContentAssignmentResult | null
  hashing: ContentHashingResult | null
}

export type IndexingCompletionStatus = 'complete' | 'complete-with-issues'

export function indexingCompletionStatus(
  progress: Pick<IndexingProgress, 'hashing'>,
): IndexingCompletionStatus {
  const hashing = progress.hashing
  return hashing !== null &&
    (hashing.skippedCount > 0 || hashing.failedCount > 0)
    ? 'complete-with-issues'
    : 'complete'
}

export class IndexingWorkflowError extends Error {
  readonly stage: IndexingStage
  readonly scanRunId: number | null
  readonly jobId: number | null
  readonly cause: unknown

  constructor(progress: IndexingProgress, cause: unknown) {
    super(`Indexing failed during ${indexingStageLabels[progress.stage]}`)
    this.name = 'IndexingWorkflowError'
    this.stage = progress.stage
    this.scanRunId = progress.scanRunId
    this.jobId = progress.jobId
    this.cause = cause
  }
}

export interface IndexingApi {
  createScanRun(sourceId: number, signal?: AbortSignal): Promise<ScanRun>
  createExecution(scanRunId: number, signal?: AbortSignal): Promise<ScanExecution>
  runDiscovery(scanRunId: number, signal?: AbortSignal): Promise<ScanExecution>
  runReconciliation(scanRunId: number, signal?: AbortSignal): Promise<ScanExecution>
  assignContent(
    scanRunId: number,
    signal?: AbortSignal,
  ): Promise<ContentAssignmentResult>
  hashContent(
    scanRunId: number,
    signal?: AbortSignal,
  ): Promise<ContentHashingResult>
}

function postJson<T>(url: string, body?: object, signal?: AbortSignal) {
  return requestJson<T>(url, {
    method: 'POST',
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  })
}

export const indexingApi: IndexingApi = {
  createScanRun(sourceId, signal) {
    return postJson<ScanRun>('/api/scan-runs', { sourceIds: [sourceId] }, signal)
  },
  createExecution(scanRunId, signal) {
    return postJson<ScanExecution>(
      `/api/scan-runs/${scanRunId}/execution`,
      undefined,
      signal,
    )
  },
  runDiscovery(scanRunId, signal) {
    return postJson<ScanExecution>(
      `/api/scan-runs/${scanRunId}/execution/discovery`,
      undefined,
      signal,
    )
  },
  runReconciliation(scanRunId, signal) {
    return postJson<ScanExecution>(
      `/api/scan-runs/${scanRunId}/execution/reconciliation`,
      undefined,
      signal,
    )
  },
  assignContent(scanRunId, signal) {
    return postJson<ContentAssignmentResult>(
      `/api/scan-runs/${scanRunId}/content-assignment`,
      undefined,
      signal,
    )
  },
  hashContent(scanRunId, signal) {
    return postJson<ContentHashingResult>(
      `/api/scan-runs/${scanRunId}/content-hashing`,
      undefined,
      signal,
    )
  },
}

function requireState(condition: boolean, message: string): void {
  if (!condition) throw new Error(message)
}

function stage(execution: ScanExecution, stageType: string): JobStage | undefined {
  return execution.stages.find((candidate) => candidate.stageType === stageType)
}

function notify(
  progress: IndexingProgress,
  onProgress: (progress: IndexingProgress) => void,
): void {
  onProgress({ ...progress })
}

export async function runSourceIndexing(
  sourceId: number,
  onProgress: (progress: IndexingProgress) => void,
  options?: {
    api?: IndexingApi
    signal?: AbortSignal
  },
): Promise<IndexingProgress> {
  const api = options?.api ?? indexingApi
  const signal = options?.signal
  const progress: IndexingProgress = {
    stage: 'PREPARING',
    scanRunId: null,
    jobId: null,
    discoveredFileCount: null,
    reconciledSourceCount: null,
    assignment: null,
    hashing: null,
  }
  notify(progress, onProgress)

  try {
    const scanRun = await api.createScanRun(sourceId, signal)
    requireState(scanRun.id > 0, 'ScanRun response did not include an ID')
    requireState(scanRun.requestType === 'INDEX', 'ScanRun was not an INDEX request')
    requireState(scanRun.status === 'PENDING', 'ScanRun was not pending')
    requireState(
      scanRun.sources.some(
        (source) => source.sourceId === sourceId && source.status === 'PENDING',
      ),
      'ScanRun did not include the requested pending Source',
    )
    progress.scanRunId = scanRun.id
    notify(progress, onProgress)

    const execution = await api.createExecution(scanRun.id, signal)
    requireState(execution.scanRunId === scanRun.id, 'Execution used a different ScanRun')
    requireState(execution.jobId > 0, 'Execution response did not include a Job ID')
    requireState(execution.jobType === 'SCAN', 'Execution was not a SCAN Job')
    requireState(execution.status === 'PENDING', 'Execution was not pending')
    requireState(
      execution.currentStageType === 'DISCOVERY' &&
        stage(execution, 'DISCOVERY')?.status === 'PENDING',
      'Execution was not ready for discovery',
    )
    progress.jobId = execution.jobId
    progress.stage = 'DISCOVERY'
    notify(progress, onProgress)

    const discovery = await api.runDiscovery(scanRun.id, signal)
    requireState(discovery.scanRunId === scanRun.id, 'Discovery used a different ScanRun')
    requireState(discovery.jobId === execution.jobId, 'Discovery used a different Job')
    requireState(
      discovery.status === 'RUNNING' &&
        discovery.currentStageType === 'RECONCILIATION' &&
        stage(discovery, 'DISCOVERY')?.status === 'COMPLETED' &&
        stage(discovery, 'RECONCILIATION')?.status === 'PENDING',
      'Discovery did not reach the reconciliation boundary',
    )
    progress.discoveredFileCount =
      stage(discovery, 'DISCOVERY')?.progressCompleted ?? null
    progress.stage = 'RECONCILIATION'
    notify(progress, onProgress)

    const reconciliation = await api.runReconciliation(scanRun.id, signal)
    requireState(
      reconciliation.scanRunId === scanRun.id,
      'Reconciliation used a different ScanRun',
    )
    requireState(
      reconciliation.jobId === execution.jobId,
      'Reconciliation used a different Job',
    )
    requireState(
      reconciliation.status === 'COMPLETED' &&
        reconciliation.currentStageType === null &&
        stage(reconciliation, 'DISCOVERY')?.status === 'COMPLETED' &&
        stage(reconciliation, 'RECONCILIATION')?.status === 'COMPLETED',
      'Reconciliation did not complete the scan execution',
    )
    progress.reconciledSourceCount =
      stage(reconciliation, 'RECONCILIATION')?.progressCompleted ?? null
    progress.stage = 'CONTENT_ASSIGNMENT'
    notify(progress, onProgress)

    const assignment = await api.assignContent(scanRun.id, signal)
    requireState(assignment.scanRunId === scanRun.id, 'Assignment used a different ScanRun')
    progress.assignment = assignment
    progress.stage = 'CONTENT_HASHING'
    notify(progress, onProgress)

    const hashing = await api.hashContent(scanRun.id, signal)
    requireState(hashing.scanRunId === scanRun.id, 'Hashing used a different ScanRun')
    progress.hashing = hashing
    progress.stage = 'COMPLETE'
    notify(progress, onProgress)
    return { ...progress }
  } catch (error: unknown) {
    throw new IndexingWorkflowError(progress, error)
  }
}
