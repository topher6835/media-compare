import { requestJson } from './http.ts'

export interface Source {
  id: number
  name: string
  rootPath: string
  locationRevision: number
  createdAtMs: number
  updatedAtMs: number
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
