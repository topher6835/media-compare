import { catalogFetch, directCatalogRuntimeId } from './connection.ts'
import { ApiError } from './http.ts'

export type CatalogOperationInput =
  | { kind: 'CLEANUP_PREFLIGHT'; digestHex: string; preflight: { keeperFileEntryId: number; candidateFileEntryIds: number[] } }
  | { kind: 'PREPARE_SOURCE'; sourceId: number }
  | { kind: 'REVEAL_FILE'; fileEntryId: number }

type State = 'RESERVED' | 'QUEUED' | 'RUNNING' | 'CANCELLING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
interface OperationStatus {
  operationId: string
  status: State
  result: unknown
  completedAtMs: number | null
  errorStatus: number | null
  errorCode: string | null
}
interface PendingOperation {
  id: string | null
  reservation: Promise<OperationStatus> | null
  discarded: boolean
}
const pending = new Map<string, PendingOperation>()
const states: State[] = ['RESERVED', 'QUEUED', 'RUNNING', 'CANCELLING', 'SUCCEEDED', 'FAILED', 'CANCELLED']

export class CatalogOperationError extends ApiError {
  readonly code: string
  readonly retryable: boolean
  constructor(status: number, code: string, retryable = false) {
    super(status)
    this.code = code
    this.retryable = retryable
    this.message = code === 'OPERATION_EXPIRED' ? 'The previous operation result expired. Reload and verify current state before trying again.'
      : code === 'CATALOG_CHANGED' ? 'The catalog changed. Reload before continuing.'
      : code === 'OPERATION_BUSY' || code === 'OPERATION_STORE_FULL' ? 'Catalog operations are busy. Wait for current work to finish, then try again.'
      : code === 'RESERVATION_UNCONFIRMED' ? 'A reservation could not be confirmed after three attempts. The server may have reserved unused handles, but no work was submitted. Try again.'
      : code === 'OPERATION_CANCELLED' ? 'The safety check was cancelled. Run a new check.'
      : 'The catalog operation could not be completed.'
  }
}

async function request(path: string, init?: RequestInit): Promise<OperationStatus> {
  const headers = new Headers(init?.headers)
  headers.set('Accept', 'application/json')
  if (init?.body !== undefined) headers.set('Content-Type', 'application/json')
  const response = await catalogFetch(`/api/catalog-operations${path}`, { ...init, headers, cache: 'no-store' })
  if (!response.ok) {
    let code = 'OPERATION_UNAVAILABLE'
    try { code = ((await response.json()) as { code?: string }).code ?? code } catch { /* Proxy failures may have no body. */ }
    throw new CatalogOperationError(response.status, code, response.status >= 500)
  }
  const status = await response.json() as OperationStatus
  if (!status || typeof status.operationId !== 'string' || !states.includes(status.status)) {
    throw new CatalogOperationError(502, 'INVALID_OPERATION_RESPONSE')
  }
  return status
}

function aborted(signal?: AbortSignal): void { signal?.throwIfAborted() }

function untilAbort<T>(promise: Promise<T>, signal?: AbortSignal): Promise<T> {
  if (!signal) return promise
  aborted(signal)
  return new Promise((resolve, reject) => {
    const onAbort = () => reject(signal.reason)
    signal.addEventListener('abort', onAbort, { once: true })
    promise.then(resolve, reject).finally(() => signal.removeEventListener('abort', onAbort))
  })
}

function pause(signal?: AbortSignal): Promise<void> {
  aborted(signal)
  return new Promise((resolve, reject) => {
    const onAbort = () => { clearTimeout(timer); reject(signal?.reason) }
    const timer = setTimeout(() => { signal?.removeEventListener('abort', onAbort); resolve() }, 1000)
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

async function retry(action: () => Promise<OperationStatus>, signal?: AbortSignal): Promise<OperationStatus> {
  for (;;) {
    aborted(signal)
    try { return await untilAbort(action(), signal) }
    catch (error) {
      aborted(signal)
      if (error instanceof CatalogOperationError && !error.retryable) throw error
      // A dropped connection does not establish whether a submission ran. Reuse the same handle.
      await pause(signal)
    }
  }
}

// An unconfirmed reservation POST may have allocated a handle, so bound only these retries.
async function reserve(current: PendingOperation, signal?: AbortSignal): Promise<string> {
  for (let attempt = 1; ; attempt++) {
    aborted(signal)
    if (current.id) return current.id
    try {
      if (!current.reservation) {
        current.reservation = request('', { method: 'POST' }).then((status) => {
          const expectedRuntime = directCatalogRuntimeId()
          if (expectedRuntime !== null && !status.operationId.startsWith(`${expectedRuntime}.`)) {
            throw new CatalogOperationError(409, 'CATALOG_CHANGED')
          }
          current.id = status.operationId
          return status
        }).finally(() => { current.reservation = null })
      }
      return (await untilAbort(current.reservation, signal)).operationId
    } catch (error) {
      aborted(signal)
      if (error instanceof CatalogOperationError && !error.retryable) throw error
      if (attempt === 3) throw new CatalogOperationError(503, 'RESERVATION_UNCONFIRMED', true)
      await pause(signal)
    }
  }
}

function requireSameOperation(status: OperationStatus, id: string): OperationStatus {
  if (status.operationId !== id) throw new CatalogOperationError(409, 'CATALOG_CHANGED')
  return status
}

/** Reservation is side-effect free; only submission of a known server handle can execute work. */
export async function runCatalogOperation<T>(input: CatalogOperationInput, signal?: AbortSignal): Promise<{ result: T; completedAtMs: number }> {
  aborted(signal)
  const key = JSON.stringify(input)
  let entry = pending.get(key)
  if (!entry) {
    if (pending.size >= 64) throw new CatalogOperationError(429, 'OPERATION_STORE_FULL')
    entry = { id: null, reservation: null, discarded: false }
    pending.set(key, entry)
  }
  const current = entry
  try {
    if (!current.id) {
      current.id = await reserve(current, signal)
    }
    const id = current.id
    const path = `/${encodeURIComponent(id)}`
    if (current.discarded) {
      // Navigation invalidated this cleanup check. Confirm actual cancellation before a fresh check.
      let cancelled = await retry(() => request(`${path}/cancel`, { method: 'POST', signal }), signal)
      while (cancelled.status === 'CANCELLING' || cancelled.status === 'RUNNING' || cancelled.status === 'QUEUED') {
        await pause(signal)
        cancelled = await retry(() => request(path, { signal }), signal)
      }
      requireSameOperation(cancelled, id)
      pending.delete(key)
      return runCatalogOperation<T>(input, signal)
    }
    let status = requireSameOperation(await retry(() => request(path, {
      method: 'POST', body: key, signal,
      headers: input.kind === 'REVEAL_FILE' ? { 'X-Media-Compare-Reveal': '1' } : undefined,
    }), signal), id)
    while (status.status === 'RESERVED' || status.status === 'QUEUED' || status.status === 'RUNNING' || status.status === 'CANCELLING') {
      await pause(signal)
      status = requireSameOperation(await retry(() => request(path, { signal }), signal), id)
    }
    aborted(signal)
    if (current.discarded) throw new CatalogOperationError(409, 'OPERATION_CANCELLED')
    pending.delete(key)
    if (status.status === 'CANCELLED') throw new CatalogOperationError(409, 'OPERATION_CANCELLED')
    if (status.status === 'FAILED') throw new CatalogOperationError(status.errorStatus ?? 500, status.errorCode ?? 'OPERATION_FAILED')
    if (status.status !== 'SUCCEEDED' || typeof status.completedAtMs !== 'number') throw new CatalogOperationError(502, 'INVALID_OPERATION_RESPONSE')
    return { result: status.result as T, completedAtMs: status.completedAtMs }
  } catch (error) {
    if (!current.id && !current.reservation && pending.get(key) === current) pending.delete(key)
    if (signal?.aborted && input.kind === 'CLEANUP_PREFLIGHT') {
      current.discarded = true
      if (current.id) {
        // Best effort only. The retained handle must be reconciled before any subsequent check.
        void request(`/${encodeURIComponent(current.id)}/cancel`, { method: 'POST' }).catch(() => {})
      }
    } else if (error instanceof CatalogOperationError && (error.status === 400
      || (error.status === 429 && error.code !== 'OPERATION_BUSY'))) {
      if (pending.get(key) === current) pending.delete(key)
    }
    throw error
  }
}
