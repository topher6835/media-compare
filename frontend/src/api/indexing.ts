import { requestJson } from './http.ts'

export type IndexingRunStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
export type IndexingStageType =
  | 'DISCOVERY'
  | 'RECONCILIATION'
  | 'CONTENT_ASSIGNMENT'
  | 'CONTENT_HASHING'

export const indexingStageLabels: Record<IndexingStageType, string> = {
  DISCOVERY: 'Discovering files',
  RECONCILIATION: 'Reconciling catalog',
  CONTENT_ASSIGNMENT: 'Assigning content',
  CONTENT_HASHING: 'Hashing content',
}

export interface IndexingStage {
  stageType: IndexingStageType
  status: IndexingRunStatus
  progressCompleted: number
  progressTotal: number | null
  startedAtMs: number | null
  finishedAtMs: number | null
  errorMessage: string | null
}

export interface AssignmentResult {
  assignedCount: number
  skippedCount: number
}

export interface HashingResult {
  hashedCount: number
  cachedCount: number
  skippedCount: number
  failedCount: number
}

export interface IndexingRunSummary {
  scanRunId: number
  jobId: number
  status: IndexingRunStatus
  currentStage: IndexingStageType | null
  progressCompleted: number
  progressTotal: number | null
  createdAtMs: number
  startedAtMs: number | null
  finishedAtMs: number | null
  errorMessage: string | null
  completedWithIssues: boolean
}

export interface IndexingRun extends IndexingRunSummary {
  requestKey: string
  sourceIds: number[]
  stages: IndexingStage[]
  assignmentResult: AssignmentResult | null
  hashingResult: HashingResult | null
}

export interface SourceIndexingStatus {
  sourceId: number
  latest: IndexingRunSummary | null
}

export interface IndexingSourceStatus {
  active: IndexingRunSummary | null
  sources: SourceIndexingStatus[]
}

export interface PendingIndexingStart {
  sourceId: number
  requestKey: string
}

let pendingIndexingStart: PendingIndexingStart | null = null

export function getPendingIndexingStart(): PendingIndexingStart | null {
  return pendingIndexingStart
}

export function rememberPendingIndexingStart(attempt: PendingIndexingStart): void {
  pendingIndexingStart = attempt
}

export function clearPendingIndexingStart(requestKey: string): void {
  if (pendingIndexingStart?.requestKey === requestKey) {
    pendingIndexingStart = null
  }
}

export function startIndexingRun(
  requestKey: string,
  sourceId: number,
): Promise<IndexingRun> {
  return requestJson<IndexingRun>('/api/indexing-runs', {
    method: 'POST',
    body: JSON.stringify({ requestKey, sourceIds: [sourceId] }),
  })
}

export function getIndexingRun(
  scanRunId: number,
  signal?: AbortSignal,
): Promise<IndexingRun> {
  return requestJson<IndexingRun>(`/api/indexing-runs/${scanRunId}`, {
    signal,
  })
}

export function getIndexingSourceStatus(
  signal?: AbortSignal,
): Promise<IndexingSourceStatus> {
  return requestJson<IndexingSourceStatus>('/api/indexing-runs/source-status', {
    signal,
  })
}

export function isActiveIndexingRun(
  run: Pick<IndexingRunSummary, 'status'>,
): boolean {
  return run.status === 'PENDING' || run.status === 'RUNNING'
}
