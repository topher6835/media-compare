import { CatalogOperationError, runCatalogOperation } from './catalogOperations.ts'
import { ApiError, requestJson } from './http.ts'

export interface Source {
  id: number
  name: string
  rootPath: string
  preparationState: 'PREPARATION_REQUIRED' | 'READY' | 'REBIND_REQUIRED'
  filesystemProfile: 'APFS' | 'NTFS' | 'EXFAT' | null
  liveAuthorityAvailable: boolean | null
  liveAuthorityWindowId: string | null
  locationRevision: number
  createdAtMs: number
  updatedAtMs: number
}

export type SourcePreparationErrorCode =
  | 'PATH_UNAVAILABLE'
  | 'PROFILE_UNSUPPORTED'
  | 'EVIDENCE_UNCERTAIN'
  | 'PROBE_ERROR'
  | 'STATE_CHANGED'

export class SourcePreparationApiError extends Error {
  readonly status: number
  readonly code: SourcePreparationErrorCode | null
  readonly operationMessage: string | null

  constructor(
    status: number,
    code: SourcePreparationErrorCode | null,
    operationMessage: string | null = null,
  ) {
    super(`Source preparation failed with status ${status}`)
    this.name = 'SourcePreparationApiError'
    this.status = status
    this.code = code
    this.operationMessage = operationMessage
  }
}

export interface RegisterSourceInput {
  name: string
  rootPath: string
}

export function validateSourceRegistration(
  input: RegisterSourceInput,
): string | null {
  if (!input.name.trim() || !input.rootPath.trim()) {
    return 'Enter both a Source name and an absolute root path.'
  }
  return null
}

export function getSources(signal?: AbortSignal): Promise<Source[]> {
  return requestJson<Source[]>('/api/sources', { signal })
}

export function registerSource(
  input: RegisterSourceInput,
  signal?: AbortSignal,
): Promise<Source> {
  return requestJson<Source>('/api/sources', {
    method: 'POST',
    body: JSON.stringify(input),
    signal,
  })
}

export async function prepareSource(sourceId: number, signal?: AbortSignal): Promise<Source> {
  try {
    await runCatalogOperation<Source>({ kind: 'PREPARE_SOURCE', sourceId }, signal)
    // A retained completion snapshot is not current live authority. Re-read the ordinary projection.
    try { return await requestJson<Source>(`/api/sources/${sourceId}`, { signal }) }
    catch (error) {
      if (signal?.aborted) throw error
      throw new SourcePreparationApiError(error instanceof ApiError ? error.status : 503, null,
        'Preparation completed, but current Source state could not be refreshed. Refresh the Source list before trying again.')
    }
  } catch (error) {
    if (error instanceof CatalogOperationError) {
      const known: SourcePreparationErrorCode[] = ['PATH_UNAVAILABLE', 'PROFILE_UNSUPPORTED', 'EVIDENCE_UNCERTAIN', 'PROBE_ERROR', 'STATE_CHANGED']
      throw new SourcePreparationApiError(error.status, known.includes(error.code as SourcePreparationErrorCode) ? error.code as SourcePreparationErrorCode : null,
        error.code.startsWith('OPERATION_') || error.code === 'CATALOG_CHANGED' ? error.message : null)
    }
    throw error
  }
}

export interface SourceAuthorityWindow { sourceId: number; windowId: string }

export function canAnalyzeSource(source: Source): boolean {
  return source.preparationState === 'READY' && (source.filesystemProfile !== 'EXFAT'
    || source.liveAuthorityAvailable === true && source.liveAuthorityWindowId !== null)
}

export function sourceAuthorityWindows(source: Source): SourceAuthorityWindow[] {
  if (!canAnalyzeSource(source)) throw new Error('Prepare/Accept this Source before analysis.')
  return source.filesystemProfile === 'EXFAT'
    ? [{ sourceId: source.id, windowId: source.liveAuthorityWindowId! }] : []
}

export interface ReleaseSourceAuthorityResult {
  source: Source
  releaseState: 'RELEASED' | 'DRAINING'
  otherWindowsOnVolumeRemain: boolean
}

export function releaseSourceAuthority(sourceId: number, windowId: string): Promise<ReleaseSourceAuthorityResult> {
  return requestJson(`/api/sources/${sourceId}/release-authority`, {
    method: 'POST', body: JSON.stringify({ windowId }),
  })
}

export function releaseAuthorityMessage(result: ReleaseSourceAuthorityResult): string {
  return `${result.releaseState === 'DRAINING' ? 'DRAINING: protected work is still closing.' : 'RELEASED: this authority has closed.'}${result.otherWindowsOnVolumeRemain
    ? ' Other authority windows on this volume remain.' : ''}`
}
