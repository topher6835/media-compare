import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFileSync } from 'node:fs'
import { registerHooks } from 'node:module'
import ts from 'typescript'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { getManagementStatus, openSession } from '../src/api/startup.ts'
import { setManagementAccess } from '../src/api/connection.ts'
import * as api from '../src/api/sessionCreations.ts'
import { CreationWorkflow } from '../src/session/creationWorkflow.ts'
import { suggestedSessionName, sessionNameError } from '../src/session/sessionName.ts'

registerHooks({ load(url, context, nextLoad) {
  if (!url.endsWith('.tsx')) return nextLoad(url, context)
  return { format: 'module', source: ts.transpileModule(readFileSync(new URL(url), 'utf8'), {
    compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ESNext },
  }).outputText, shortCircuit: true }
} })
const { SessionStartupPage } = await import('../src/session/SessionStartupPage.tsx')
const { NewSessionPanel } = await import('../src/session/NewSessionPanel.tsx')
const runtime = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const opId = (n) => `${runtime}.bbbbbbbb-bbbb-4bbb-8bbb-${String(n).padStart(12, '0')}`
const status = { service: 'media-compare-management', catalogState: 'NOT_STARTED', processAlive: false,
  failureCode: null, exitCode: null, catalogId: null }
function operation(n = 1, patch = {}) {
  return { operationId: opId(n), creationId: opId(n), latestAttemptId: opId(n), kind: 'CREATE', state: 'RESERVED',
    sessionCreated: false, executionActive: false, catalogId: null, errorCode: null, stopping: false,
    parentPath: null, requestedName: null, name: null, path: null, attemptedPath: null, directoryCreated: false,
    previousAttemptId: null, message: null, openingFailureCode: null, catalogMatchesCurrent: false, catalogState: null,
    processAlive: null, ...patch }
}
function created(n = 1, patch = {}) {
  return operation(n, { state: 'OPEN_FAILED', sessionCreated: true, directoryCreated: true,
    name: 'Family café (001)', path: '/parent/Family café (001)', parentPath: '/parent', requestedName: 'Family café', ...patch })
}
function server(t, records = [], options = {}) {
  const requests = [], stored = new Map()
  const previousWindow = globalThis.window
  globalThis.window = { sessionStorage: {
    getItem: (key) => { if (options.noStorage) throw Error('Disabled'); return stored.get(key) ?? null },
    setItem: (key, value) => { if (options.noStorage) throw Error('Disabled'); stored.set(key, value) },
  } }
  t.after(() => { if (previousWindow === undefined) delete globalThis.window; else globalThis.window = previousWindow })
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    const path = url.replace('/api/management/session-creations', '')
    requests.push({ path, ...init })
    if (options.handler) {
      const result = await options.handler(path, init, records)
      if (result !== undefined) return result
    }
    if (path === '' && init.method === 'GET') return Response.json({ runtimeId: options.runtime ?? runtime, stopping: !!options.stopping, operations: records })
    if (path === '' && init.method === 'POST') {
      const op = operation(records.length + 1); records.push(op)
      if (options.loseReservation) throw Error('Reservation response lost')
      return Response.json(op)
    }
    const op = records.find((item) => path === '/' + item.operationId)
    if (op && init.method === 'GET') return Response.json(op)
    if (op && init.method === 'POST') {
      const input = new URLSearchParams(init.body)
      Object.assign(op, { parentPath: input.get('parentPath'), requestedName: input.get('name'), state: 'CREATING', executionActive: true })
      if (options.loseSubmission) throw Error('Submission response lost')
      return Response.json(op)
    }
    return Response.json({ code: 'OPERATION_EXPIRED' }, { status: 410 })
  })
  setManagementAccess('test-capability')
  return { records, requests, stored }
}

test('portable local-time suggestion; validation preserves Unicode and rejects unsafe names and byte lengths', () => {
  assert.equal(suggestedSessionName(new Date(2026, 9, 28, 21, 57)), 'Session 2026-10-28 21-57')
  for (const name of ['Family café 日本 + # %', 'Session 2026-10-28 21-57', 'a'.repeat(249)]) assert.equal(sessionNameError(name), null)
  for (const name of ['', ' ', '.', '..', 'bad/name', 'bad\\name', 'bad:', 'bad\u0001', 'bad\u0085', 'NUL.txt', 'COM¹', 'CONOUT$', 'end.', 'end ', 'a'.repeat(250), '日'.repeat(84), '\ud800']) assert.ok(sessionNameError(name), name)
})

test('forms use exact authenticated content type and encode the actual parent and name', async (t) => {
  const s = server(t)
  await api.discoverCreations()
  const op = await api.reserveCreation()
  const parent = 'C:\\Family café 日本 + # %', name = 'New café + # %'
  await api.submitCreation(op.operationId, parent, name)
  const sent = s.requests.at(-1)
  assert.equal(sent.headers.get('X-Media-Compare-Access'), 'test-capability')
  assert.equal(sent.headers.get('Content-Type'), 'application/x-www-form-urlencoded;charset=UTF-8')
  assert.equal(new URLSearchParams(sent.body).get('parentPath'), parent)
  assert.equal(new URLSearchParams(sent.body).get('name'), name)
})

test('HTTP errors keep status and code but never expose provider diagnostics', async (t) => {
  server(t, [], { handler: () => Response.json({ code: 'LIFECYCLE_BUSY', message: 'RAW PROVIDER SECRET' }, { status: 409 }) })
  await assert.rejects(api.reserveCreation(), (error) => error.status === 409 && error.code === 'LIFECYCLE_BUSY' && !error.message.includes('RAW'))
})

test('unsafe response fields and wrong operation identities are rejected', async (t) => {
  let response
  server(t, [], { handler: () => Response.json(response) })
  for (const patch of [{ executionActive: 'false' }, { sessionCreated: true }, { catalogMatchesCurrent: true }, { operationId: 'unsafe' }, { kind: 'DELETE' }, { state: 'SUCCESS' }]) {
    response = operation(1, patch)
    await assert.rejects(api.getCreation(opId(1)), /Unsupported/)
  }
  response = operation(2)
  await assert.rejects(api.getCreation(opId(1)), /Unsupported/)
})

test('readiness requires successful operation AND current matching RUNNING/alive catalog', () => {
  const ready = created(1, { state: 'RUNNING', catalogId: 'launch', catalogMatchesCurrent: true, catalogState: 'RUNNING', processAlive: true })
  const live = { ...status, catalogState: 'RUNNING', processAlive: true, catalogId: 'launch' }
  assert.equal(api.creationCatalogReady(ready, live), true)
  for (const patch of [{ state: 'OPENING' }, { sessionCreated: false }, { catalogMatchesCurrent: false }, { catalogState: 'FAILED' }, { processAlive: false }, { catalogId: 'historical' }]) assert.equal(api.creationCatalogReady({ ...ready, ...patch }, live), false)
  for (const patch of [{ catalogId: 'other' }, { processAlive: false }, { catalogState: 'STARTING' }]) assert.equal(api.creationCatalogReady(ready, { ...live, ...patch }), false)
})

test('startup enables New only after management discovery and preserves lifecycle guards', async (t) => {
  server(t)
  const workflow = new CreationWorkflow()
  const render = () => renderToStaticMarkup(createElement(SessionStartupPage, { status, creation: workflow, creationView: workflow.getSnapshot() }))
  assert.match(render(), /disabled="">New Session/)
  await workflow.refresh(status)
  assert.match(render(), /<button type="button">New Session/)
  for (const patch of [{ catalogState: 'STOPPING' }, { processAlive: true }, { catalogState: 'STARTING' }]) {
    await workflow.refresh({ ...status, ...patch })
    assert.match(render(), /disabled="">New Session/)
  }
})

test('reserve is retained before immutable submission; repeated clicks reserve/submit once', async (t) => {
  const s = server(t, [], { handler: (path, init) => {
    if (path && init.method === 'POST') {
      const ids = JSON.parse(s.stored.get('media-compare-creation'))
      assert.equal(ids.root, opId(1)); assert.equal(ids.attempt, opId(1))
    }
  } })
  const workflow = new CreationWorkflow()
  await workflow.refresh(status)
  await Promise.all([workflow.create('/parent', 'Name'), workflow.create('/parent', 'Name')])
  assert.equal(s.requests.filter((r) => r.method === 'POST' && !r.path).length, 1)
  assert.equal(s.requests.filter((r) => r.method === 'POST' && r.path).length, 1)
  assert.equal(workflow.getSnapshot().attempt.state, 'CREATING')
  await workflow.create('/parent', 'Different')
  assert.equal(s.requests.filter((r) => r.method === 'POST').length, 2)
})

test('lost submission response polls same handle; refresh resumes without POST', async (t) => {
  const s = server(t, [], { loseSubmission: true })
  const workflow = new CreationWorkflow()
  await workflow.refresh(status)
  await workflow.create('/parent', 'Name')
  assert.equal(workflow.getSnapshot().attempt.state, 'CREATING')
  const posts = s.requests.filter((r) => r.method === 'POST').length
  const refreshed = new CreationWorkflow()
  await refreshed.refresh(status)
  assert.equal(refreshed.getSnapshot().attempt.operationId, opId(1))
  assert.equal(s.requests.filter((r) => r.method === 'POST').length, posts)
})

test('lost reservation response discovers RESERVED without automatically submitting', async (t) => {
  const s = server(t, [], { loseReservation: true })
  const workflow = new CreationWorkflow()
  await workflow.refresh(status)
  await workflow.create('/parent', 'Name')
  assert.equal(workflow.getSnapshot().root.state, 'RESERVED')
  assert.equal(s.requests.filter((r) => r.method === 'POST').length, 1)
  const refreshed = new CreationWorkflow()
  await refreshed.refresh(status)
  assert.equal(refreshed.getSnapshot().root.state, 'RESERVED')
  assert.equal(s.requests.filter((r) => r.method === 'POST').length, 1)
})

test('bound RESERVED needs explicit submission with original input', async (t) => {
  const s = server(t, [operation(1, { parentPath: '/original', requestedName: 'Original' })])
  const workflow = new CreationWorkflow()
  await workflow.refresh(status)
  assert.equal(s.requests.filter((r) => r.method === 'POST').length, 0)
  await workflow.create('/elsewhere', 'Changed')
  assert.equal(s.requests.filter((r) => r.method === 'POST').length, 0)
  await workflow.refresh(status)
  await workflow.create('/original', 'Original')
  assert.equal(s.requests.filter((r) => r.method === 'POST').length, 1)
})

test('storage-disabled discovery resumes unique work; multiple roots require a choice', async (t) => {
  const s = server(t, [operation(1, { state: 'CREATING', executionActive: true })], { noStorage: true })
  const workflow = new CreationWorkflow()
  await workflow.refresh(status)
  assert.equal(workflow.getSnapshot().attempt.operationId, opId(1))
  s.records.push(operation(2))
  const refreshed = new CreationWorkflow()
  await refreshed.refresh(status)
  assert.equal(refreshed.getSnapshot().root, null)
  assert.equal(refreshed.canStart(), false)
  assert.equal(refreshed.holdsStartup(), true)
  await refreshed.choose(opId(1))
  assert.equal(refreshed.getSnapshot().root.operationId, opId(1))
  assert.equal(s.requests.filter((r) => r.method === 'POST').length, 0)
})

test('expired and previous-runtime handles block replay and explain manual recovery', async (t) => {
  const s = server(t)
  const workflow = new CreationWorkflow()
  await workflow.refresh(status)
  await workflow.create('/parent', 'Name')
  s.records.length = 0
  const refreshed = new CreationWorkflow()
  await refreshed.refresh(status)
  assert.match(refreshed.getSnapshot().error, /expired/)
  assert.equal(refreshed.canStart(), false)
  await refreshed.create('/parent', 'Name')
  assert.equal(s.requests.filter((r) => r.method === 'POST').length, 2)
  s.stored.set('media-compare-creation', JSON.stringify({ runtime: 'old-runtime', root: opId(1), attempt: opId(1), dismissed: [] }))
  const restarted = new CreationWorkflow()
  await restarted.refresh(status)
  assert.match(restarted.getSnapshot().error, /restarted/)
  assert.equal(restarted.canStart(), false)
})

test('actual collision-resolved path/name and progress render; uncertain creation never offers retry', async (t) => {
  const s = server(t, [created()])
  const workflow = new CreationWorkflow()
  const render = () => renderToStaticMarkup(createElement(NewSessionPanel, { workflow, view: workflow.getSnapshot(), status, onClose() {} }))
  for (const [state, catalogId, text] of [['CREATING', null, /Creating Session/], ['OPENING', null, /Session created; opening catalog/], ['OPENING', 'launch', /Catalog initialization in progress/], ['OPEN_FAILED', null, /catalog opening failed/]]) {
    Object.assign(s.records[0], { state, catalogId })
    await workflow.refresh(status)
    assert.match(render(), text); assert.match(render(), /Family café \(001\)/)
  }
  Object.assign(s.records[0], operation(1, { state: 'CREATION_UNCERTAIN', directoryCreated: true, attemptedPath: '/parent/partial' }))
  await workflow.refresh(status)
  assert.match(render(), /uncertain or incomplete/)
  assert.match(render(), /\/parent\/partial/)
  assert.doesNotMatch(render(), /Retry Opening/)
  assert.equal(workflow.canRetry(), false)
})

test('retry waits for exit, uses latest failed attempt, never creates; second failure needs deliberate retry', async (t) => {
  const root = created()
  const s = server(t, [root], { handler(path, init, records) {
    if (path.endsWith('/retry-open')) {
      assert.equal(new URLSearchParams(init.body).get('failedAttemptId'), root.latestAttemptId)
      const retry = created(records.length + 1, { kind: 'RETRY_OPEN', state: 'RESERVED', creationId: root.operationId, previousAttemptId: root.latestAttemptId })
      records.push(retry); return Response.json(retry)
    }
    if (path.endsWith('/open')) {
      const retry = records.find((r) => path === '/' + r.operationId + '/open')
      root.latestAttemptId = retry.operationId
      Object.assign(retry, { state: 'OPEN_FAILED' }); return Response.json(retry)
    }
  } })
  const workflow = new CreationWorkflow()
  await workflow.refresh({ ...status, catalogState: 'FAILED', processAlive: true })
  await workflow.retryOpening()
  assert.equal(s.requests.filter((r) => r.method === 'POST').length, 0)
  await workflow.refresh({ ...status, catalogState: 'FAILED' })
  await Promise.all([workflow.retryOpening(), workflow.retryOpening()])
  assert.equal(s.requests.filter((r) => r.path.endsWith('/retry-open')).length, 1)
  assert.equal(workflow.getSnapshot().attempt.operationId, opId(2))
  await workflow.refresh(status)
  assert.equal(s.requests.filter((r) => r.method === 'POST').length, 2)
  await workflow.retryOpening()
  assert.equal(s.requests.filter((r) => r.method === 'POST').length, 4)
  assert.ok(s.requests.filter((r) => r.method === 'POST').every((r) => r.path.endsWith('/open') || r.path.endsWith('/retry-open')))
})

test('lost retry reservation is discovered; refreshed retry requires explicit submission', async (t) => {
  const root = created()
  const s = server(t, [root], { handler(path, init, records) {
    if (path.endsWith('/retry-open')) {
      records.push(created(2, { kind: 'RETRY_OPEN', state: 'RESERVED', creationId: root.operationId, previousAttemptId: root.operationId }))
      throw Error('Lost retry reservation')
    }
    if (path.endsWith('/open')) {
      root.latestAttemptId = opId(2)
      Object.assign(records[1], { state: 'OPENING', executionActive: true }); return Response.json(records[1])
    }
  } })
  const workflow = new CreationWorkflow()
  await workflow.refresh(status)
  await workflow.retryOpening()
  assert.equal(workflow.getSnapshot().attempt.state, 'RESERVED')
  const refreshed = new CreationWorkflow()
  await refreshed.refresh(status)
  assert.equal(s.requests.filter((r) => r.method === 'POST').length, 1)
  await refreshed.submitReservedRetry()
  assert.equal(refreshed.getSnapshot().attempt.state, 'OPENING')
  assert.equal(s.requests.filter((r) => r.method === 'POST').length, 2)
})

test('poll failure holds startup; consumed navigation does not reopen Sources on refresh', async (t) => {
  let offline = false
  const ready = created(1, { state: 'RUNNING', catalogId: 'launch', catalogMatchesCurrent: true, catalogState: 'RUNNING', processAlive: true })
  const live = { ...status, catalogState: 'RUNNING', processAlive: true, catalogId: 'launch' }
  server(t, [ready], { handler() { if (offline) throw Error('Network unavailable') } })
  const workflow = new CreationWorkflow()
  await workflow.refresh(live)
  assert.equal(workflow.ready(live), true)
  offline = true
  await workflow.refresh(live)
  assert.equal(workflow.ready(live), false); assert.equal(workflow.holdsStartup(), true)
  offline = false
  await workflow.refresh(live)
  workflow.completed()
  assert.equal(workflow.holdsStartup(), false)
  const refreshed = new CreationWorkflow()
  await refreshed.refresh(live)
  assert.equal(refreshed.ready(live), false); assert.equal(refreshed.holdsStartup(), false)
})

test('stale sibling reservations never replace the newest failed opening attempt', async (t) => {
  const root = created(1, { latestAttemptId: opId(3) })
  const stale = created(2, { kind: 'RETRY_OPEN', creationId: opId(1), previousAttemptId: opId(1), state: 'RESERVED' })
  const latest = created(3, { kind: 'RETRY_OPEN', creationId: opId(1), previousAttemptId: opId(1) })
  server(t, [root, stale, latest])
  const workflow = new CreationWorkflow()
  await workflow.refresh(status)
  assert.equal(workflow.getSnapshot().attempt.operationId, opId(3))
  assert.equal(workflow.canRetry(), true)
})

test('ambiguous sibling retries require inspection; stopping and active work block New/Open admission', async (t) => {
  const root = created()
  const retry = (n) => created(n, { kind: 'RETRY_OPEN', creationId: opId(1), previousAttemptId: opId(1), state: 'RESERVED' })
  const s = server(t, [root, retry(2), retry(3)])
  const workflow = new CreationWorkflow()
  await workflow.refresh(status)
  assert.match(workflow.getSnapshot().error, /Multiple opening reservations/)
  assert.equal(workflow.canRetry(), false)
  s.records.splice(0, 3, operation(1, { stopping: true, state: 'CREATING', executionActive: true }))
  const refreshed = new CreationWorkflow()
  await refreshed.refresh(status)
  assert.equal(refreshed.canStart(), false)
})

test('retained-handle verification completes before another creation can be admitted', async (t) => {
  let release
  const gate = new Promise(resolve => { release = resolve })
  const s = server(t, [], { handler: async (path, init) => {
    if (path === '/' + opId(1) && init.method === 'GET') { await gate; return Response.json(operation()) }
  } })
  s.stored.set('media-compare-creation', JSON.stringify({ runtime, root: opId(1), attempt: opId(1), dismissed: [] }))
  const workflow = new CreationWorkflow()
  const refreshing = workflow.refresh(status)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(workflow.canStart(), false)
  await workflow.create('/another', 'Another')
  assert.equal(s.requests.filter(r => r.method === 'POST').length, 0)
  release(); await refreshing
  assert.equal(workflow.getSnapshot().root.operationId, opId(1))
})

test('available management with failed discovery offers acknowledged manual Open and pins its returned launch', async (t) => {
  let current = status
  const s = server(t, [], { handler(path) {
    if (path === '/api/management/status') return Response.json(current)
    if (path === '/api/management/open') {
      current = { ...status, catalogState: 'CREATED', catalogId: 'manual-launch' }
      return Response.json(current)
    }
    return Response.json({ code: 'RESULT_UNAVAILABLE' }, { status: 503 })
  } })
  const workflow = new CreationWorkflow()
  const available = await getManagementStatus()
  await workflow.refresh(available)
  const render = (managementAvailable = true) => renderToStaticMarkup(createElement(SessionStartupPage, {
    status: available, creation: workflow, creationView: workflow.getSnapshot(), managementAvailable,
  }))
  assert.match(render(), /disabled="">Open Existing Session/)
  assert.match(render(), /I understand; use manual Open/)
  assert.match(render(), /does not cancel it or resolve its outcome/)
  assert.equal(workflow.holdsStartup(), true)
  assert.equal(workflow.manualCatalogReady({ ...status, catalogState: 'RUNNING', processAlive: true, catalogId: 'unrelated' }), false)
  workflow.acknowledgeManualOpen()
  assert.match(render(), /<button type="button">Open Existing Session/)
  assert.match(render(), /disabled="">New Session/)
  assert.match(render(false), /disabled="">Open Existing Session/)
  await workflow.refresh(available) // Continuing discovery failure must not close the manual path.
  assert.equal(workflow.canOpenExisting(available), true)
  await workflow.create('/parent', 'No replay')
  assert.equal(s.requests.filter(r => r.method === 'POST').length, 0)
  const opened = await openSession('/existing-valid-session')
  workflow.observeManualOpen(opened)
  assert.equal(workflow.manualCatalogReady(opened), false)
  current = { ...current, catalogState: 'RUNNING', processAlive: true }
  const live = await getManagementStatus()
  await workflow.refresh(live)
  assert.equal(workflow.manualCatalogReady(live), true)
  assert.equal(workflow.ready(live), false) // Manual Open never triggers New-only Sources navigation.
  assert.equal(workflow.manualCatalogReady({ ...live, catalogId: 'unrelated' }), false)
  assert.equal(workflow.manualCatalogReady({ ...live, processAlive: false }), false)
  assert.equal(s.requests.filter(r => r.method === 'POST').length, 1)
})

test('manual recovery acknowledgment preserves handles and known active/lifecycle restrictions', async (t) => {
  let unavailable = false
  const s = server(t, [operation(1, { state: 'CREATING', executionActive: true })], {
    handler() { if (unavailable) throw Error('Creation polling unavailable') },
  })
  const workflow = new CreationWorkflow()
  await workflow.refresh(status)
  const idsBefore = s.stored.get('media-compare-creation')
  unavailable = true
  await workflow.refresh(status)
  workflow.acknowledgeManualOpen()
  assert.equal(s.stored.get('media-compare-creation'), idsBefore)
  assert.equal(workflow.getSnapshot().root.operationId, opId(1))
  assert.equal(workflow.canOpenExisting(status), false)
  assert.equal(workflow.canStart(), false)
  assert.equal(s.requests.filter(r => r.method === 'POST').length, 0)
})

test('manual Open after a detail-poll failure still blocks live, starting and stopping catalogs', async (t) => {
  server(t, [created()], { handler(path) {
    if (path === '/' + opId(1)) return Response.json({ code: 'RESULT_UNAVAILABLE' }, { status: 503 })
  } })
  const workflow = new CreationWorkflow()
  await workflow.refresh(status)
  workflow.acknowledgeManualOpen()
  assert.equal(workflow.canOpenExisting(status), true)
  for (const patch of [{ processAlive: true }, { catalogState: 'CREATED' }, { catalogState: 'STARTING' }, { catalogState: 'STOPPING' }, { catalogState: 'RUNNING' }]) {
    assert.equal(workflow.canOpenExisting({ ...status, ...patch }), false)
  }
})

test('backend definitive rejection messages remain distinct from uncertainty, expiry, restart and transport failures', () => {
  const definitive = {
    UNSUPPORTED_STORAGE: /rejected.*storage.*APFS.*NTFS/,
    PARENT_UNAVAILABLE: /rejected.*parent folder.*missing or inaccessible/,
    CREATION_REJECTED: /Creation was rejected/,
    OPERATION_INPUT_CHANGED: /rejected.*original parent/,
    OPERATION_KIND_CHANGED: /rejected.*different operation/,
    OPERATION_STORE_FULL: /rejected.*history is full/,
    INVALID_CONTENT_TYPE: /rejected.*request format/,
    REQUEST_TOO_LARGE: /rejected.*too large/,
    RETRY_SUPERSEDED: /rejected.*newer attempt/,
    RETRY_NOT_AVAILABLE: /rejected.*latest failed opening/,
    INVALID_INPUT: /valid portable Session name/,
  }
  for (const [code, expected] of Object.entries(definitive)) {
    const message = api.creationErrorMessage(code)
    assert.match(message, expected, code)
    assert.doesNotMatch(message, /could not be confirmed|lost connection|uncertain or incomplete/, code)
  }
  assert.match(api.creationErrorMessage('CREATION_UNCERTAIN'), /uncertain or incomplete/)
  assert.match(api.creationErrorMessage('OPERATION_EXPIRED'), /expired/)
  assert.match(api.creationErrorMessage('MANAGEMENT_RUNTIME_CHANGED'), /restarted/)
  assert.match(api.creationErrorMessage('RESULT_UNAVAILABLE'), /result is unavailable.*may have completed/)
  assert.match(api.creationErrorMessage('CATALOG_OPEN_FAILED'), /Session was created.*opening failed/)
  assert.match(api.creationErrorMessage('CATALOG_LAUNCH_CHANGED'), /launch changed/)
  assert.match(api.creationErrorMessage(null), /could not be confirmed.*lost connection/)
})
