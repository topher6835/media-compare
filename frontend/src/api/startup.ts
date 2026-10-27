import { identifyDirectCatalogRuntime, managementHeaders, setManagementAccess } from './connection.ts'

export type StartupMode = 'management' | 'catalog'
export type CatalogState = 'NOT_STARTED' | 'CREATED' | 'STARTING' | 'RUNNING' | 'STOPPING' | 'FAILED'
const catalogStates = ['NOT_STARTED', 'CREATED', 'STARTING', 'RUNNING', 'STOPPING', 'FAILED']
export interface ManagementStatus {
  service: 'media-compare-management'
  catalogState: CatalogState
  processAlive: boolean
  failureCode: string | null
  exitCode: number | null
  catalogId: string | null
}

export async function getStartupMode(signal?: AbortSignal): Promise<StartupMode> {
  const response = await fetch('/api/management/bootstrap', {
    headers: { 'X-Media-Compare-Bootstrap': '1', Accept: 'application/json' },
    cache: 'no-store', signal,
  })
  if (response.status === 404) {
    const runtime = await fetch('/api/catalog-operations', { headers: { Accept: 'application/json' }, cache: 'no-store', signal })
    if (!runtime.ok) throw new Error('Catalog runtime is unavailable')
    const context = await runtime.json() as { runtimeId?: string }
    if (typeof context?.runtimeId !== 'string' || !/^[0-9a-f-]{36}$/.test(context.runtimeId)) throw new Error('Unsupported catalog runtime')
    identifyDirectCatalogRuntime(context.runtimeId)
    return 'catalog'
  }
  if (!response.ok) throw new Error('Management connection is unavailable')
  const bootstrap = await response.json() as { service?: string; capability?: string }
  if (bootstrap?.service !== 'media-compare-management' || typeof bootstrap.capability !== 'string'
    || bootstrap.capability.length < 32) throw new Error('Unsupported management connection')
  setManagementAccess(bootstrap.capability)
  return 'management'
}

export async function managementRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = managementHeaders()
  if (init?.body !== undefined) headers.set('Content-Type', 'text/plain;charset=UTF-8')
  const response = await fetch(`/api/management/${path}`, { ...init, headers, cache: 'no-store' })
  if (!response.ok) {
    let message = `Management request failed (${response.status}).`
    try {
      const body = await response.json() as { message?: string }
      if (body.message) message = body.message
    } catch { /* Unavailable service may return no JSON. */ }
    throw new Error(message)
  }
  return await response.json() as T
}

export async function getManagementStatus(signal?: AbortSignal): Promise<ManagementStatus> {
  const status = await managementRequest<ManagementStatus>('status', { signal })
  if (status?.service !== 'media-compare-management' || !catalogStates.includes(status.catalogState)
    || typeof status.processAlive !== 'boolean'
    || !(status.catalogId === null || typeof status.catalogId === 'string')
    || status.catalogState === 'RUNNING' && (!status.processAlive || !status.catalogId)) {
    throw new Error('Unsupported management status')
  }
  return status
}

export function openSession(path: string): Promise<ManagementStatus> {
  return managementRequest('open', { method: 'POST', body: path })
}

export function catalogFailureMessage(code: string | null): string {
  switch (code) {
    case 'INVALID_SESSION': return 'This Session is unsupported, invalid or no longer accessible.'
    case 'OWNERSHIP_FAILED': return 'The Session could not be acquired. It may already be open in another Media Compare process, or its lock is inaccessible.'
    case 'INITIALIZATION_FAILED': return 'The catalog could not initialize. It may be damaged or incompatible. You can choose another Session.'
    case 'STARTUP_TIMEOUT': return 'The catalog took too long to initialize. Cleanup has been requested.'
    case 'SHUTDOWN_TIMEOUT': return 'The catalog has not finished shutting down. Another Session cannot open until the process exits.'
    default: return 'The catalog is unavailable. You can choose another Session after its process exits.'
  }
}
