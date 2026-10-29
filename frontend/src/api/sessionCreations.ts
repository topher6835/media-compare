import { managementHeaders } from './connection.ts'
import type { CatalogState, ManagementStatus } from './startup.ts'

export type CreationState = 'RESERVED' | 'CREATING' | 'OPENING' | 'RUNNING' | 'REJECTED' | 'CREATION_UNCERTAIN' | 'OPEN_FAILED'
export interface CreationSummary {
  operationId: string
  creationId: string
  latestAttemptId: string
  kind: 'CREATE' | 'RETRY_OPEN'
  state: CreationState
  sessionCreated: boolean
  executionActive: boolean
  catalogId: string | null
  errorCode: string | null
}
export interface CreationOperation extends CreationSummary {
  stopping: boolean
  parentPath: string | null
  requestedName: string | null
  name: string | null
  path: string | null
  attemptedPath: string | null
  directoryCreated: boolean
  previousAttemptId: string | null
  message: string | null
  openingFailureCode: string | null
  catalogMatchesCurrent: boolean
  catalogState: CatalogState | null
  processAlive: boolean | null
}
export interface CreationDiscovery { runtimeId: string; stopping: boolean; operations: CreationSummary[] }
const uuid = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
const handle = new RegExp(`^${uuid}\\.${uuid}$`)
const states = ['RESERVED', 'CREATING', 'OPENING', 'RUNNING', 'REJECTED', 'CREATION_UNCERTAIN', 'OPEN_FAILED']
const catalogStates = ['NOT_STARTED', 'CREATED', 'STARTING', 'RUNNING', 'STOPPING', 'FAILED']
function invalid(): never { throw new Error('Unsupported Session operation response. Inspect the Session before creating again.') }
function nullableString(value: unknown): boolean { return value === null || typeof value === 'string' }
function summary(value: CreationSummary): CreationSummary {
  if (!value || typeof value.operationId !== 'string' || typeof value.creationId !== 'string' || typeof value.latestAttemptId !== 'string' || !handle.test(value.operationId) || !handle.test(value.creationId) || !handle.test(value.latestAttemptId)
    || !['CREATE', 'RETRY_OPEN'].includes(value.kind) || !states.includes(value.state)
    || typeof value.sessionCreated !== 'boolean' || typeof value.executionActive !== 'boolean'
    || !nullableString(value.catalogId) || !nullableString(value.errorCode)
    || value.operationId.split('.')[0] !== value.creationId.split('.')[0]
    || value.operationId.split('.')[0] !== value.latestAttemptId.split('.')[0]
    || value.kind === 'CREATE' && value.creationId !== value.operationId) invalid()
  return value
}
function operation(value: CreationOperation): CreationOperation {
  summary(value)
  if (typeof value.stopping !== 'boolean' || typeof value.directoryCreated !== 'boolean'
    || typeof value.catalogMatchesCurrent !== 'boolean'
    || !(value.processAlive === null || typeof value.processAlive === 'boolean')
    || !(value.catalogState === null || catalogStates.includes(value.catalogState))
    || ![value.parentPath, value.requestedName, value.name, value.path, value.attemptedPath,
      value.previousAttemptId, value.message, value.openingFailureCode].every(nullableString)
    || value.sessionCreated !== (typeof value.path === 'string' && value.path.length > 0)
    || value.sessionCreated && !value.name
    || value.kind === 'RETRY_OPEN' && (!value.previousAttemptId || !handle.test(value.previousAttemptId))
    || value.catalogMatchesCurrent && !value.catalogId) invalid()
  return value
}
export class CreationHttpError extends Error {
  status: number
  code: string
  constructor(status: number, code: string) {
    super(creationErrorMessage(code)); this.status = status; this.code = code
  }
}
export function creationErrorMessage(code: string | null): string {
  switch (code) {
    case 'UNSUPPORTED_STORAGE': return 'Creation was rejected because Session storage is unsupported. Select supported local macOS/APFS or Windows/NTFS storage.'
    case 'PARENT_UNAVAILABLE': return 'Creation was rejected because the parent folder is missing or inaccessible. Check that it exists and that you can access it.'
    case 'OPERATION_INPUT_CHANGED': return 'The request was rejected because this reservation already has different input. Use its original parent and Session name.'
    case 'OPERATION_KIND_CHANGED': return 'The request was rejected because this handle is for a different operation. Creation and Retry Opening use separate handles.'
    case 'OPERATION_STORE_FULL': return 'The reservation was rejected because retained operation history is full. Inspect retained operations or wait for unused reservations to expire.'
    case 'INVALID_CONTENT_TYPE': return 'The submission was rejected because its request format is unsupported. Reload the interface before trying again.'
    case 'REQUEST_TOO_LARGE': return 'The submission was rejected because the request is too large. Check the parent path and Session name lengths.'
    case 'CATALOG_OPEN_FAILED': return 'The Session was created, but catalog opening failed. Wait for the process to fully exit, then explicitly retry opening or open the Session manually.'
    case 'CATALOG_LAUNCH_CHANGED': return 'The opening attempt failed because its catalog launch changed. Inspect the current catalog before retrying opening.'
    case 'RESULT_UNAVAILABLE': return 'The operation result is unavailable. Inspect the retained operation and Session manually before retrying; work may have completed.'
    case 'INVALID_INPUT': return 'Enter an existing absolute parent path and a valid portable Session name.'
    case 'LIFECYCLE_BUSY': case 'MANAGEMENT_BUSY': case 'CATALOG_BUSY': return 'Management is busy. Wait and check the retained operation before continuing.'
    case 'MANAGEMENT_STOPPING': return 'Management is stopping. Work may still be completing; inspect the retained Session after restart.'
    case 'MANAGEMENT_RUNTIME_CHANGED': return 'Management restarted. The old operation cannot be recovered. Inspect the selected parent and manually open a valid Session; do not replay creation.'
    case 'OPERATION_EXPIRED': case 'OPERATION_GONE': return 'The operation handle expired. Inspect the selected parent and manually open a valid Session before deliberately creating again.'
    case 'RETRY_CHILD_ALIVE': return 'Wait for the previous catalog process to fully exit before retrying opening.'
    case 'RETRY_SUPERSEDED': return 'The opening retry was rejected because a newer attempt exists. Inspect the latest attempt before deliberately retrying.'
    case 'RETRY_NOT_AVAILABLE': return 'The opening retry was rejected because it does not identify the latest failed opening of a created Session. Inspect the retained operation.'
    case 'CREATION_REJECTED': return 'Creation was rejected. Check the name, parent access, supported local APFS/NTFS storage and available names.'
    case 'CREATION_UNCERTAIN': return 'Creation is uncertain or incomplete. Inspect the attempted folder; it may contain a partial or valid Session. Do not create a replacement automatically.'
    default: return 'The request could not be confirmed. Check the retained operation; a lost connection does not mean the work failed.'
  }
}
async function request(path = '', fields?: Record<string, string>, post = false, signal?: AbortSignal): Promise<unknown> {
  const headers = managementHeaders()
  if (fields) headers.set('Content-Type', 'application/x-www-form-urlencoded;charset=UTF-8')
  const response = await fetch(`/api/management/session-creations${path}`, {
    method: post ? 'POST' : 'GET', headers, cache: 'no-store', signal,
    ...(fields ? { body: new URLSearchParams(fields).toString() } : {}),
  })
  if (!response.ok) {
    let code = 'REQUEST_FAILED'
    try { const body = await response.json(); if (typeof body?.code === 'string') code = body.code } catch { /* No provider diagnostics. */ }
    throw new CreationHttpError(response.status, code)
  }
  return response.json()
}
export async function discoverCreations(signal?: AbortSignal): Promise<CreationDiscovery> {
  const value = await request('', undefined, false, signal) as CreationDiscovery
  if (!value || typeof value.runtimeId !== 'string' || !new RegExp(`^${uuid}$`).test(value.runtimeId)
    || typeof value.stopping !== 'boolean' || !Array.isArray(value.operations) || value.operations.length > 64) invalid()
  value.operations.forEach((item) => { summary(item); if (!item.operationId.startsWith(value.runtimeId + '.')) invalid() })
  return value
}
async function operationRequest(path: string, fields?: Record<string, string>, post = false, signal?: AbortSignal) {
  return operation(await request(path, fields, post, signal) as CreationOperation)
}
function idPath(id: string): string { if (!handle.test(id)) invalid(); return '/' + encodeURIComponent(id) }
export async function reserveCreation() {
  const value = await operationRequest('', undefined, true)
  if (value.kind !== 'CREATE' || value.state !== 'RESERVED' || value.sessionCreated || value.executionActive) invalid()
  return value
}
export async function getCreation(id: string, signal?: AbortSignal) {
  const value = await operationRequest(idPath(id), undefined, false, signal)
  if (value.operationId !== id) invalid()
  return value
}
export async function submitCreation(id: string, parentPath: string, name: string) {
  const value = await operationRequest(idPath(id), { parentPath, name }, true)
  if (value.operationId !== id || value.kind !== 'CREATE' || value.parentPath !== parentPath || value.requestedName !== name) invalid()
  return value
}
export async function reserveOpeningRetry(root: string, failedAttemptId: string) {
  const value = await operationRequest(idPath(root) + '/retry-open', { failedAttemptId }, true)
  if (value.kind !== 'RETRY_OPEN' || value.creationId !== root || value.previousAttemptId !== failedAttemptId || value.state !== 'RESERVED') invalid()
  return value
}
export async function submitOpeningRetry(id: string) {
  const value = await operationRequest(idPath(id) + '/open', undefined, true)
  if (value.operationId !== id || value.kind !== 'RETRY_OPEN') invalid()
  return value
}

export function creationCatalogReady(attempt: CreationOperation, status: ManagementStatus): boolean {
  return attempt.state === 'RUNNING' && attempt.sessionCreated && !!attempt.catalogId
    && attempt.catalogMatchesCurrent && attempt.catalogState === 'RUNNING' && attempt.processAlive === true
    && status.catalogId === attempt.catalogId && status.catalogState === 'RUNNING' && status.processAlive
}
