import * as api from '../api/sessionCreations.ts'
import type { ManagementStatus } from '../api/startup.ts'

const storageKey = 'media-compare-creation'
interface RecoveryIds { runtime: string | null; root: string | null; attempt: string | null; dismissed: string[] }
export interface CreationView {
  checked: boolean
  manualOpen: boolean
  storageAvailable: boolean
  busy: boolean
  blocked: boolean
  stopping: boolean
  activeElsewhere: boolean
  roots: api.CreationSummary[]
  root: api.CreationOperation | null
  attempt: api.CreationOperation | null
  error: string | null
}
const initialView: CreationView = { checked: false, manualOpen: false, storageAvailable: true, busy: false, blocked: false, stopping: false,
  activeElsewhere: false, roots: [], root: null, attempt: null, error: null }

/** Document-local observer. Only explicit actions submit; polling never executes work. */
export class CreationWorkflow {
  private view = initialView
  private ids: RecoveryIds = { runtime: null, root: null, attempt: null, dismissed: [] }
  private listeners = new Set<() => void>()
  private revision = 0
  private status: ManagementStatus | null = null
  private manualCatalogId: string | null = null
  private input: { parent: string; name: string } | null = null
  constructor() {
    try {
      const value = JSON.parse(window.sessionStorage.getItem(storageKey) ?? 'null') as RecoveryIds | null
      if (value && (value.runtime === null || typeof value.runtime === 'string')
        && (value.root === null || typeof value.root === 'string') && (value.attempt === null || typeof value.attempt === 'string')
        && Array.isArray(value.dismissed) && value.dismissed.every((id) => typeof id === 'string')) this.ids = value
    } catch { this.view = { ...this.view, storageAvailable: false } }
  }
  getSnapshot = () => this.view
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private update(patch: Partial<CreationView>) {
    this.view = { ...this.view, ...patch }
    this.listeners.forEach((listener) => listener())
  }
  private persist() {
    try { window.sessionStorage.setItem(storageKey, JSON.stringify(this.ids)) } catch {
      if (this.view.storageAvailable) this.update({ storageAvailable: false })
    }
  }
  private retain(op: api.CreationOperation) {
    if (op.operationId.split('.')[0] !== this.ids.runtime) throw new Error(api.creationErrorMessage('MANAGEMENT_RUNTIME_CHANGED'))
    this.ids.root = op.creationId
    this.ids.attempt = op.operationId
    this.persist() // Before any execution request, including retry.
    this.update(op.kind === 'CREATE' ? { root: op, attempt: op } : { attempt: op })
  }
  private failure(failure: unknown, blocked = false) {
    this.update({ error: failure instanceof Error ? failure.message : api.creationErrorMessage(null), blocked })
  }
  async refresh(status: ManagementStatus, signal?: AbortSignal) {
    this.status = status
    if (this.view.busy) return
    const revision = this.revision
    try {
      const list = await api.discoverCreations(signal)
      if (signal?.aborted || revision !== this.revision) return
      if (this.ids.runtime && this.ids.runtime !== list.runtimeId) {
        this.update({ checked: true, blocked: true, stopping: list.stopping, error: api.creationErrorMessage('MANAGEMENT_RUNTIME_CHANGED') })
        return
      }
      this.ids.runtime = list.runtimeId
      const roots = list.operations.filter((op) => op.kind === 'CREATE' && !this.ids.dismissed.includes(op.operationId))
      this.update({ roots, stopping: list.stopping, activeElsewhere: list.operations.some((op) => op.executionActive) })
      if (this.view.blocked) {
        // A dropped reservation response is recoverable through discovery, never execution.
        if (!this.ids.root && roots.length === 1) this.update({ blocked: false })
        else { this.update({ checked: true }); return }
      }
      if (!this.ids.root && roots.length === 1) this.ids.root = roots[0].operationId
      this.persist()
      if (this.ids.root) {
        const root = await api.getCreation(this.ids.root, signal)
        let attempt = root.latestAttemptId === root.operationId ? root : await api.getCreation(root.latestAttemptId, signal)
        // Only reservations against today's exact failed attempt are eligible. Older siblings stay inert.
        if (attempt.state === 'OPEN_FAILED') {
          const summaries = list.operations.filter((op) => op.kind === 'RETRY_OPEN'
            && op.creationId === root.operationId && op.state === 'RESERVED')
          const reserved = (await Promise.all(summaries.map((op) => api.getCreation(op.operationId, signal))))
            .filter((op) => op.state === 'RESERVED' && op.previousAttemptId === root.latestAttemptId)
          const known = reserved.find((op) => op.operationId === this.ids.attempt)
          if (known) attempt = known
          else if (reserved.length === 1) attempt = reserved[0]
          else if (reserved.length > 1) throw new Error('Multiple opening reservations exist. Return to startup, inspect the Session and open it manually.')
        }
        if (signal?.aborted || revision !== this.revision) return
        if (attempt.creationId !== root.operationId) throw new Error('The opening attempt does not belong to this Session.')
        this.ids.attempt = attempt.operationId
        this.persist()
        this.update({ checked: true, root, attempt, error: null })
      } else this.update({ checked: true, error: null })
    } catch (failure) {
      if (signal?.aborted || revision !== this.revision) return
      const expired = failure instanceof api.CreationHttpError && (failure.status === 410 || failure.code === 'MANAGEMENT_RUNTIME_CHANGED')
      this.failure(failure, expired || this.view.blocked)
    }
  }
  canStart(): boolean {
    return !this.view.manualOpen && this.view.checked && !this.view.busy && !this.view.blocked && !this.view.stopping
      && !this.view.activeElsewhere && this.status !== null && !this.status.processAlive
      && ['NOT_STARTED', 'FAILED'].includes(this.status.catalogState) && !this.view.error
      && (this.view.roots.length === 0 || this.view.root?.state === 'RESERVED')
  }
  async choose(id: string) {
    if (this.view.busy || !this.view.roots.some((op) => op.operationId === id)) return
    this.revision++
    this.ids.root = id; this.ids.attempt = null; this.persist()
    this.update({ root: null, attempt: null, error: null, blocked: false })
    if (this.status) await this.refresh(this.status)
  }
  async create(parent: string, name: string) {
    if (!this.canStart()) return
    const existing = this.view.root
    if (existing && (existing.state !== 'RESERVED' || existing.kind !== 'CREATE')) return
    if (existing?.parentPath !== null && existing?.parentPath !== undefined
      && (existing.parentPath !== parent || existing.requestedName !== name)) {
      this.failure(new Error('This reservation already has immutable input. Use its original parent and name.')); return
    }
    this.revision++
    this.update({ busy: true, error: null })
    this.input = { parent, name }
    try {
      const op = existing ?? await api.reserveCreation()
      this.retain(op)
      this.retain(await api.submitCreation(op.operationId, parent, name))
    } catch (failure) {
      // With no returned reservation handle, discovery must precede another deliberate creation.
      this.failure(failure, !this.ids.root)
    } finally { this.update({ busy: false }); if (this.status) await this.refresh(this.status) }
  }
  reservedInput() {
    const root = this.view.root
    return root?.parentPath && root.requestedName ? { parent: root.parentPath, name: root.requestedName } : this.input
  }
  canRetry(): boolean {
    return !this.view.manualOpen && this.view.checked && !this.view.error && !this.view.busy && !this.view.blocked && !this.view.stopping && !this.view.activeElsewhere
      && this.view.root?.sessionCreated === true && this.view.attempt?.state === 'OPEN_FAILED'
      && this.view.root.latestAttemptId === this.view.attempt.operationId
      && this.status !== null && !this.status.processAlive && ['NOT_STARTED', 'FAILED'].includes(this.status.catalogState)
  }
  async retryOpening() {
    if (!this.canRetry()) return
    const root = this.view.root!, failed = this.view.attempt!
    this.revision++
    this.update({ busy: true, error: null })
    try {
      const op = await api.reserveOpeningRetry(root.operationId, failed.operationId)
      this.retain(op)
      this.retain(await api.submitOpeningRetry(op.operationId))
    } catch (failure) { this.failure(failure) }
    finally { this.update({ busy: false }); if (this.status) await this.refresh(this.status) }
  }
  async submitReservedRetry() {
    const attempt = this.view.attempt
    if (this.view.manualOpen || !this.view.checked || this.view.error || this.view.busy || this.view.blocked || this.view.stopping || this.view.activeElsewhere || this.status?.processAlive
      || attempt?.kind !== 'RETRY_OPEN' || attempt.state !== 'RESERVED') return
    this.revision++
    this.update({ busy: true, error: null })
    try { this.retain(await api.submitOpeningRetry(attempt.operationId)) }
    catch (failure) { this.failure(failure) }
    finally { this.update({ busy: false }); if (this.status) await this.refresh(this.status) }
  }
  ready(status: ManagementStatus): boolean {
    return !this.view.manualOpen && !this.view.busy && !this.view.blocked && !this.view.error && !!this.view.attempt
      && api.creationCatalogReady(this.view.attempt, status)
  }
  /** Acknowledgment permits manual Open, without discarding recovery evidence or resolving uncertainty. */
  acknowledgeManualOpen() {
    if (this.view.busy || !this.view.error) return
    this.revision++
    this.manualCatalogId = null
    this.update({ manualOpen: true })
  }
  canOpenExisting(status: ManagementStatus): boolean {
    return !this.view.busy && !this.view.activeElsewhere && !this.view.stopping && !status.processAlive
      && ['NOT_STARTED', 'FAILED'].includes(status.catalogState)
      && (this.view.manualOpen || this.view.checked && !this.view.blocked && !this.view.error
        && !this.view.root && this.view.roots.length === 0)
  }
  observeManualOpen(status: ManagementStatus) {
    if (this.view.manualOpen && typeof status.catalogId === 'string' && status.catalogId) {
      this.manualCatalogId = status.catalogId
    }
  }
  manualCatalogReady(status: ManagementStatus): boolean {
    return this.view.manualOpen && this.manualCatalogId !== null && status.catalogId === this.manualCatalogId
      && status.catalogState === 'RUNNING' && status.processAlive && !this.view.busy && !this.view.activeElsewhere
  }
  holdsStartup(): boolean {
    return !this.view.checked || this.view.busy || this.view.blocked || !!this.view.root || this.view.roots.length > 0 || !!this.view.error
  }
  /** Explicitly detach; this is never server cancellation or authorization to replay. */
  returnToStartup() {
    if (this.view.busy) return
    this.revision++
    this.ids.dismissed = [...new Set([...this.ids.dismissed, ...this.view.roots.map((op) => op.operationId), ...(this.ids.root ? [this.ids.root] : [])])].slice(-64)
    this.ids.root = null; this.ids.attempt = null
    if (this.view.blocked) this.ids.runtime = null
    this.persist()
    this.update({ root: null, attempt: null, roots: [], error: null, blocked: false })
    this.input = null
  }
  completed() { this.returnToStartup() }
}
