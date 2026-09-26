import { requestJson } from './http.ts'

export interface Source {
  id: number
  name: string
  rootPath: string
  preparationState: 'PREPARATION_REQUIRED' | 'READY' | 'REBIND_REQUIRED'
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

  constructor(
    status: number,
    code: SourcePreparationErrorCode | null,
  ) {
    super(`Source preparation failed with status ${status}`)
    this.name = 'SourcePreparationApiError'
    this.status = status
    this.code = code
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

export async function prepareSource(sourceId: number): Promise<Source> {
  const response = await fetch(`/api/sources/${sourceId}/prepare`, {
    method: 'POST',
    headers: { Accept: 'application/json' },
  })
  if (!response.ok) {
    let code: SourcePreparationErrorCode | null = null
    try {
      const body = (await response.json()) as { code?: SourcePreparationErrorCode }
      code = body.code ?? null
    } catch {
      // Network and server errors may have no JSON response.
    }
    throw new SourcePreparationApiError(response.status, code)
  }
  return (await response.json()) as Source
}
