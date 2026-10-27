import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  aggregateCleanupSafety,
  cleanupSafetyLabels,
  cleanupSafetyReasonMessage,
  findCleanupFileResult,
  invalidateCleanupSafetyResults,
} from '../src/duplicates/duplicateCleanupSafety.ts'
import { ApiError, checkExactDuplicateCleanupSafety } from '../src/api/exactDuplicates.ts'

function result(status = 'READY', reason = null) {
  return {
    digestHex: 'a'.repeat(64), status, reason, sizeBytes: 100,
    candidateCount: 2, estimatedSavingsBytes: status === 'READY' ? 200 : null,
    keeper: { fileEntryId: 10, status: 'READY', reason: null },
    candidates: [
      { fileEntryId: 30, status, reason },
      { fileEntryId: 20, status: 'READY', reason: null },
    ],
  }
}

test('maps every backend reason to a specific useful explanation', () => {
  const expected = {
    GROUP_CHANGED: 'The exact duplicate group changed; review it again.',
    KEEPER_UNAVAILABLE: 'The planned keeper is no longer currently available.',
    CANDIDATE_SET_CHANGED: 'Current present copies no longer match the saved plan.',
    AUTHORITY_UNAVAILABLE: 'Current storage authority could not be verified.',
    AUTHORITY_CHANGED: 'Source or storage authority changed.',
    FILESYSTEM_CHANGED: 'File metadata or identity changed.',
    UNSAFE_PATH: 'The path or file structure no longer meets cleanup safety requirements.',
    HASH_MISMATCH: 'Current file bytes no longer match the exact duplicate digest.',
    IO_UNAVAILABLE: 'The file could not currently be read or inspected.',
  }
  for (const [reason, message] of Object.entries(expected)) {
    assert.equal(cleanupSafetyReasonMessage(reason), message)
  }
  assert.equal(cleanupSafetyReasonMessage(null), 'Safety requirements were not met; review the group again.')
  assert.equal(cleanupSafetyLabels.READY, 'CHECK PASSED')
  assert.notEqual(cleanupSafetyLabels.BLOCKED, cleanupSafetyLabels.CHECK_FAILED)
})

test('aggregates only current planned groups and keeps failures and checks distinct from ready/blocked', () => {
  const states = {
    ready: { status: 'READY', result: result() },
    blocked: { status: 'BLOCKED', result: result('BLOCKED', 'HASH_MISMATCH') },
    failed: { status: 'CHECK_FAILED' },
    checking: { status: 'CHECKING' },
    unchecked: { status: 'NOT_CHECKED' },
    removed: { status: 'READY', result: result() },
  }
  assert.deepEqual(aggregateCleanupSafety(['ready', 'blocked', 'failed', 'checking', 'unchecked', 'new'], states), {
    readyNow: 1, blocked: 1, notChecked: 2, failed: 1, checking: 1,
  })
  assert.deepEqual(aggregateCleanupSafety([], states), {
    readyNow: 0, blocked: 0, notChecked: 0, failed: 0, checking: 0,
  })
})

test('a retry stops counting the previous result as ready', () => {
  const states = { a: { status: 'READY', result: result() } }
  assert.equal(aggregateCleanupSafety(['a'], states).readyNow, 1)
  states.a = { status: 'CHECKING' }
  assert.equal(aggregateCleanupSafety(['a'], states).readyNow, 0)
  states.a = { status: 'CHECK_FAILED' }
  assert.equal(aggregateCleanupSafety(['a'], states).blocked, 0)
  assert.equal(aggregateCleanupSafety(['a'], states).failed, 1)
})

test('whole-plan rerun invalidates all targeted results and counts while preserving unrelated state', () => {
  const digests = ['ready', 'blocked', 'failed']
  const states = {
    ready: { status: 'READY', result: result() },
    blocked: { status: 'BLOCKED', result: result('BLOCKED', 'HASH_MISMATCH') },
    failed: { status: 'CHECK_FAILED' },
    unrelated: { status: 'READY', result: result() },
  }
  const before = structuredClone(states)
  assert.deepEqual(aggregateCleanupSafety(digests, states), {
    readyNow: 1, blocked: 1, notChecked: 0, failed: 1, checking: 0,
  })

  const nextStates = invalidateCleanupSafetyResults(digests, states)
  for (const digest of digests) {
    assert.deepEqual(nextStates[digest], { status: 'NOT_CHECKED' })
  }
  assert.deepEqual(aggregateCleanupSafety(digests, nextStates), {
    readyNow: 0, blocked: 0, notChecked: 3, failed: 0, checking: 0,
  })
  assert.equal(nextStates.unrelated, states.unrelated)
  assert.deepEqual(aggregateCleanupSafety([...digests, 'unrelated'], nextStates), {
    readyNow: 1, blocked: 0, notChecked: 3, failed: 0, checking: 0,
  })
  assert.deepEqual(states, before)

  nextStates.ready = { status: 'CHECKING' }
  assert.deepEqual(aggregateCleanupSafety(digests, nextStates), {
    readyNow: 0, blocked: 0, notChecked: 2, failed: 0, checking: 1,
  })
})

test('matches keeper/candidates by physical ID only, independent of array order or group status', () => {
  const response = result('BLOCKED', 'HASH_MISMATCH')
  const before = structuredClone(response)
  assert.equal(findCleanupFileResult(response, 10), response.keeper)
  assert.equal(findCleanupFileResult(response, 20), response.candidates[1])
  assert.equal(findCleanupFileResult(response, 20).status, 'READY')
  assert.equal(findCleanupFileResult(response, 30).reason, 'HASH_MISMATCH')
  assert.equal(findCleanupFileResult(response, 0), undefined)
  assert.equal(findCleanupFileResult(response, 1), undefined)
  assert.equal(findCleanupFileResult(undefined, 10), undefined)
  assert.deepEqual(response, before)
})

test('async submission sends only physical IDs and returns the completion observation time', async (t) => {
  const response = result()
  const controller = new AbortController()
  const calls = []
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url, init })
    if (url === '/api/catalog-operations') return Response.json({ operationId: 'runtime.safety', status: 'RESERVED' })
    assert.equal(url, '/api/catalog-operations/runtime.safety')
    assert.equal(init.method, 'POST')
    assert.equal(init.headers.get('Content-Type'), 'application/json')
    assert.equal(init.signal, controller.signal)
    assert.deepEqual(JSON.parse(init.body), { kind: 'CLEANUP_PREFLIGHT', digestHex: 'a/b', preflight: { keeperFileEntryId: 10, candidateFileEntryIds: [20, 30] } })
    return Response.json({ operationId: 'runtime.safety', status: 'SUCCEEDED', result: response, completedAtMs: 1234 })
  })
  assert.deepEqual(await checkExactDuplicateCleanupSafety('a/b', {
    keeperFileEntryId: 10, candidateFileEntryIds: [20, 30], paths: ['untrusted'], sizeBytes: 999,
  }, controller.signal), { ...response, checkedAtMs: 1234 })
  assert.equal(calls.length, 2)
})

test('backend BLOCKED is an observation, while terminal operation failure rejects for the UI', async (t) => {
  const blocked = result('BLOCKED', 'IO_UNAVAILABLE')
  const request = { keeperFileEntryId: 10, candidateFileEntryIds: [20, 30] }
  const fetchMock = t.mock.method(globalThis, 'fetch', async (url) => Response.json({
    operationId: 'runtime.blocked', status: url === '/api/catalog-operations' ? 'RESERVED' : 'SUCCEEDED', result: blocked, completedAtMs: 1234,
  }))
  assert.deepEqual(await checkExactDuplicateCleanupSafety('a', request), { ...blocked, checkedAtMs: 1234 })
  fetchMock.mock.mockImplementation(async (url) => Response.json({ operationId: 'runtime.failure',
    status: url === '/api/catalog-operations' ? 'RESERVED' : 'FAILED', errorStatus: 503, errorCode: 'OPERATION_FAILED' }))
  await assert.rejects(checkExactDuplicateCleanupSafety('a', request), error => error instanceof ApiError && error.status === 503)
})
