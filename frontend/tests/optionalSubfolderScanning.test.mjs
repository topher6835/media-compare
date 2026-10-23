import assert from 'node:assert/strict'
import { test } from 'node:test'
import { startIndexingRun, rememberPendingIndexingStart, getPendingIndexingStart, clearPendingIndexingStart } from '../src/api/indexing.ts'

test('recursive default and explicit limited requests retain their captured scope on retry', async () => {
  const originalFetch = globalThis.fetch
  const requests = []
  globalThis.fetch = async (_url, init) => {
    requests.push(JSON.parse(init.body))
    return { ok: true, json: async () => ({}) }
  }
  try {
    await startIndexingRun('recursive-key', 3)
    assert.equal(requests[0].includeSubfolders, true)
    const attempt = { sourceId: 3, requestKey: 'limited-key', includeSubfolders: false }
    rememberPendingIndexingStart(attempt)
    await startIndexingRun(attempt.requestKey, attempt.sourceId, [], attempt.includeSubfolders)
    const retained = getPendingIndexingStart()
    await startIndexingRun(retained.requestKey, retained.sourceId, [], retained.includeSubfolders)
    assert.deepEqual(requests[1], { requestKey: 'limited-key', includeSubfolders: false, sourceIds: [3] })
    assert.deepEqual(requests[1], requests[2])
    clearPendingIndexingStart(attempt.requestKey)
    assert.equal(getPendingIndexingStart(), null)
  } finally {
    globalThis.fetch = originalFetch
  }
})
