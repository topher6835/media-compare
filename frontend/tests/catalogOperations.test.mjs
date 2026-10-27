import assert from 'node:assert/strict'
import { test } from 'node:test'
import { CatalogOperationError, runCatalogOperation } from '../src/api/catalogOperations.ts'
import { prepareSource, SourcePreparationApiError } from '../src/api/sources.ts'

const snapshot = (id, status, result = null, errorStatus = null, errorCode = null) => ({
  operationId: id, status, result, errorStatus, errorCode, completedAtMs: ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(status) ? 1234 : null,
})
function fastPolling(t) {
  const original = globalThis.setTimeout
  t.mock.method(globalThis, 'setTimeout', (action, _delay, ...args) => original(action, 1, ...args))
}

test('direct development uses relative reserve/submit/poll calls and waits for the original result', async (t) => {
  fastPolling(t)
  const calls = []
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url, init })
    assert.equal(init.headers.get('X-Media-Compare-Access'), null)
    assert.equal(init.headers.get('X-Media-Compare-Catalog'), null)
    return Response.json(snapshot('runtime.first', calls.length === 1 ? 'RESERVED' : calls.length === 2 ? 'RUNNING' : 'SUCCEEDED', { done: true }))
  })
  const result = await runCatalogOperation({ kind: 'PREPARE_SOURCE', sourceId: 100 })
  assert.deepEqual(result, { result: { done: true }, completedAtMs: 1234 })
  assert.deepEqual(calls.map(c => [c.url, c.init.method ?? 'GET']), [
    ['/api/catalog-operations', 'POST'], ['/api/catalog-operations/runtime.first', 'POST'], ['/api/catalog-operations/runtime.first', 'GET'],
  ])
})

test('a lost submission response retries the same handle without reserving or executing twice', async (t) => {
  fastPolling(t)
  let reserves = 0, submissions = 0, polls = 0
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    if (url === '/api/catalog-operations') { reserves++; return Response.json(snapshot('runtime.retry', 'RESERVED')) }
    assert.equal(url, '/api/catalog-operations/runtime.retry')
    if (init.method === 'POST') {
      submissions++
      if (submissions === 1) throw new TypeError('response lost after acceptance')
      return Response.json(snapshot('runtime.retry', 'RUNNING'))
    }
    polls++
    if (polls === 1) return new Response(null, { status: 503 })
    return Response.json(snapshot('runtime.retry', 'SUCCEEDED', 'original result'))
  })
  assert.equal((await runCatalogOperation({ kind: 'REVEAL_FILE', fileEntryId: 101 })).result, 'original result')
  assert.equal(reserves, 1)
  assert.equal(submissions, 2)
  assert.equal(polls, 2)
})

test('terminal failure propagates its original status and code without transport retries', async (t) => {
  let calls = 0
  t.mock.method(globalThis, 'fetch', async () => {
    calls++
    return Response.json(snapshot('runtime.failed', calls === 1 ? 'RESERVED' : 'FAILED', null, 502, 'FINDER_FAILED'))
  })
  await assert.rejects(runCatalogOperation({ kind: 'REVEAL_FILE', fileEntryId: 102 }),
    error => error instanceof CatalogOperationError && error.status === 502 && error.code === 'FINDER_FAILED')
  assert.equal(calls, 2)
})

test('expiry or catalog replacement never silently allocates a replacement operation', async (t) => {
  let reserves = 0
  t.mock.method(globalThis, 'fetch', async (url) => {
    if (url === '/api/catalog-operations') { reserves++; return Response.json(snapshot('runtime.expired', 'RESERVED')) }
    return Response.json({ code: 'OPERATION_EXPIRED' }, { status: 410 })
  })
  const input = { kind: 'REVEAL_FILE', fileEntryId: 103 }
  for (let attempt = 0; attempt < 2; attempt++) await assert.rejects(runCatalogOperation(input), /expired/)
  assert.equal(reserves, 1)
})

test('navigation detaches from side effects; returning resumes the same operation', async (t) => {
  const controller = new AbortController()
  let reserves = 0, submissions = 0, cancels = 0
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    if (url === '/api/catalog-operations') { reserves++; return Response.json(snapshot('runtime.reveal', 'RESERVED')) }
    if (url.endsWith('/cancel')) { cancels++; return Response.json(snapshot('runtime.reveal', 'CANCELLED')) }
    submissions++
    if (submissions === 1) {
      queueMicrotask(() => controller.abort())
      return Response.json(snapshot('runtime.reveal', 'RUNNING'))
    }
    return Response.json(snapshot('runtime.reveal', 'SUCCEEDED'))
  })
  const input = { kind: 'REVEAL_FILE', fileEntryId: 104 }
  await assert.rejects(runCatalogOperation(input, controller.signal), error => error.name === 'AbortError')
  assert.equal((await runCatalogOperation(input)).result, null)
  assert.equal(reserves, 1)
  assert.equal(cancels, 0)
})

test('leaving cleanup invalidates its observation; another explicit check reconciles cancellation then runs fresh', async (t) => {
  const controller = new AbortController()
  let reserves = 0, oldCancelled = false, cancels = 0
  const input = { kind: 'CLEANUP_PREFLIGHT', digestHex: 'a'.repeat(64), preflight: { keeperFileEntryId: 1, candidateFileEntryIds: [2] } }
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    if (url === '/api/catalog-operations') {
      reserves++
      if (reserves === 2) assert.equal(oldCancelled, true)
      return Response.json(snapshot(`runtime.check${reserves}`, 'RESERVED'))
    }
    if (url.endsWith('/cancel')) {
      cancels++
      return Response.json(snapshot('runtime.check1', oldCancelled ? 'CANCELLED' : 'CANCELLING'))
    }
    if (url.endsWith('check1') && init.method === 'POST') {
      queueMicrotask(() => controller.abort())
      return Response.json(snapshot('runtime.check1', 'RUNNING'))
    }
    return Response.json(snapshot('runtime.check2', 'SUCCEEDED', { status: 'BLOCKED' }))
  })
  await assert.rejects(runCatalogOperation(input, controller.signal), error => error.name === 'AbortError')
  oldCancelled = true
  assert.deepEqual((await runCatalogOperation(input)).result, { status: 'BLOCKED' })
  assert.equal(reserves, 2)
  assert(cancels >= 1)
})

test('simultaneous callers share reservation and operation identity', async (t) => {
  let reserves = 0
  const executions = new Set()
  t.mock.method(globalThis, 'fetch', async (url) => {
    if (url === '/api/catalog-operations') { reserves++; return Response.json(snapshot('runtime.shared', 'RESERVED')) }
    executions.add(url)
    return Response.json(snapshot('runtime.shared', 'SUCCEEDED', true))
  })
  const input = { kind: 'PREPARE_SOURCE', sourceId: 105 }
  const results = await Promise.all([runCatalogOperation(input), runCatalogOperation(input)])
  assert(results.every(result => result.result === true))
  assert.equal(reserves, 1)
  assert.equal(executions.size, 1)
})

test('preparation re-reads live projection after completion and preserves domain error codes', async (t) => {
  let sourceReads = 0
  t.mock.method(globalThis, 'fetch', async (url) => {
    if (url === '/api/catalog-operations') return Response.json(snapshot('runtime.prepare', 'RESERVED'))
    if (url === '/api/sources/106') { sourceReads++; return Response.json({ id: 106, liveAuthorityAvailable: false }) }
    return Response.json(snapshot('runtime.prepare', 'SUCCEEDED', { id: 106, liveAuthorityAvailable: true }))
  })
  assert.equal((await prepareSource(106)).liveAuthorityAvailable, false)
  assert.equal(sourceReads, 1)
  t.mock.method(globalThis, 'fetch', async (url) => Response.json(snapshot('runtime.unavailable',
    url === '/api/catalog-operations' ? 'RESERVED' : 'FAILED', null, 422, 'PATH_UNAVAILABLE')))
  await assert.rejects(prepareSource(107), error => error instanceof SourcePreparationApiError && error.code === 'PATH_UNAVAILABLE' && error.status === 422)
})

test('busy submission retains its reservation for the next explicit retry', async (t) => {
  let reserves = 0, submissions = 0
  const bodies = []
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    if (url === '/api/catalog-operations') {
      reserves++
      return Response.json(snapshot('runtime.busy', 'RESERVED'))
    }
    assert.equal(url, '/api/catalog-operations/runtime.busy')
    bodies.push(init.body)
    submissions++
    if (submissions === 1) return Response.json({ code: 'OPERATION_BUSY' }, { status: 429 })
    return Response.json(snapshot('runtime.busy', 'SUCCEEDED', 'finished'))
  })
  const input = { kind: 'PREPARE_SOURCE', sourceId: 201 }
  await assert.rejects(runCatalogOperation(input), error => error.status === 429 && error.code === 'OPERATION_BUSY')
  assert.equal(submissions, 1) // Busy requires an explicit user retry.
  assert.equal((await runCatalogOperation(input)).result, 'finished')
  assert.equal(reserves, 1)
  assert.deepEqual(bodies, [JSON.stringify(input), JSON.stringify(input)])
})

test('invalid input and other 429 rejections release their pending reservations', async (t) => {
  for (const [status, code] of [[400, 'INVALID_OPERATION_REQUEST'], [429, 'OPERATION_STORE_FULL']]) {
    let reserves = 0, submissions = 0
    t.mock.method(globalThis, 'fetch', async (url) => {
      if (url === '/api/catalog-operations') {
        reserves++
        return Response.json(snapshot(`runtime.rejected${reserves}`, 'RESERVED'))
      }
      submissions++
      if (submissions === 1) return Response.json({ code }, { status })
      assert.equal(url, '/api/catalog-operations/runtime.rejected2')
      return Response.json(snapshot('runtime.rejected2', 'SUCCEEDED', true))
    })
    const input = { kind: 'PREPARE_SOURCE', sourceId: status }
    await assert.rejects(runCatalogOperation(input), error => error.status === status && error.code === code)
    assert.equal((await runCatalogOperation(input)).result, true)
    assert.equal(reserves, 2)
  }
})

test('lost reservation responses stop at three attempts, release capacity and allow explicit retry', async (t) => {
  fastPolling(t)
  let reserves = 0, submissions = 0, unavailable = true
  t.mock.method(globalThis, 'fetch', async (url) => {
    if (url === '/api/catalog-operations') {
      reserves++
      if (unavailable) throw new TypeError('reservation response lost')
      return Response.json(snapshot('runtime.confirmed', 'RESERVED'))
    }
    submissions++
    return Response.json(snapshot('runtime.confirmed', 'SUCCEEDED', true))
  })
  const input = { kind: 'PREPARE_SOURCE', sourceId: 202 }
  // Exceed the map capacity to prove failed reservations do not accumulate.
  for (let i = 0; i < 65; i++) {
    const before = reserves
    await assert.rejects(runCatalogOperation({ ...input, sourceId: input.sourceId + i }), error =>
      error instanceof CatalogOperationError && error.code === 'RESERVATION_UNCONFIRMED'
      && error.retryable && /may have reserved unused handles/.test(error.message)
      && /no work was submitted/.test(error.message) && /Try again/.test(error.message))
    assert.equal(reserves - before, 3)
  }
  assert.equal(submissions, 0)
  unavailable = false
  assert.equal((await runCatalogOperation(input)).result, true)
  assert.equal(reserves, 196)
  assert.equal(submissions, 1)
})

test('concurrent callers share reservation attempts after a lost response', async (t) => {
  fastPolling(t)
  let reserves = 0
  t.mock.method(globalThis, 'fetch', async (url) => {
    if (url === '/api/catalog-operations') {
      reserves++
      await new Promise(resolve => setImmediate(resolve))
      if (reserves === 1) throw new TypeError('response lost')
      return Response.json(snapshot('runtime.shared-retry', 'RESERVED'))
    }
    assert.equal(url, '/api/catalog-operations/runtime.shared-retry')
    return Response.json(snapshot('runtime.shared-retry', 'SUCCEEDED', true))
  })
  const input = { kind: 'PREPARE_SOURCE', sourceId: 301 }
  const results = await Promise.all([runCatalogOperation(input), runCatalogOperation(input)])
  assert(results.every(result => result.result === true))
  assert.equal(reserves, 2)
})

test('aborting during reservation retry delay stops attempts and permits later retry', async (t) => {
  const controller = new AbortController()
  let reserves = 0
  const original = globalThis.setTimeout
  t.mock.method(globalThis, 'setTimeout', (action, _delay, ...args) => {
    queueMicrotask(() => controller.abort())
    return original(action, 1, ...args)
  })
  t.mock.method(globalThis, 'fetch', async (url) => {
    if (url === '/api/catalog-operations') {
      reserves++
      if (reserves === 1) throw new TypeError('response lost')
      return Response.json(snapshot('runtime.after-abort', 'RESERVED'))
    }
    return Response.json(snapshot('runtime.after-abort', 'SUCCEEDED', true))
  })
  const input = { kind: 'PREPARE_SOURCE', sourceId: 302 }
  await assert.rejects(runCatalogOperation(input, controller.signal), error => error.name === 'AbortError')
  assert.equal(reserves, 1)
  assert.equal((await runCatalogOperation(input)).result, true)
  assert.equal(reserves, 2)
})

test('a replaced direct-development catalog cannot accept an action from the old document', async (t) => {
  const { identifyDirectCatalogRuntime } = await import('../src/api/connection.ts')
  identifyDirectCatalogRuntime('old-runtime')
  let submissions = 0
  t.mock.method(globalThis, 'fetch', async (url) => {
    if (url !== '/api/catalog-operations') submissions++
    return Response.json(snapshot('replacement-runtime.request', 'RESERVED'))
  })
  await assert.rejects(runCatalogOperation({ kind: 'REVEAL_FILE', fileEntryId: 999 }),
    error => error instanceof CatalogOperationError && error.code === 'CATALOG_CHANGED')
  assert.equal(submissions, 0)
})
