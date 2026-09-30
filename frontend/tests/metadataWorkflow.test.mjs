import assert from 'node:assert/strict'
import { test } from 'node:test'
import { metadataAction, metadataHasIssues } from '../src/sources/metadataWorkflow.ts'

const run = (status, createdAtMs, result = null) => ({
  jobId: 7, status, createdAtMs, errorMessage: null,
  stage: { candidatesAttempted: 0, result },
})

test('fresh completed indexing starts metadata exactly when no newer pass exists', () => {
  assert.equal(metadataAction(200, { active: null, latest: null }), 'start')
  assert.equal(metadataAction(200, { active: null, latest: run('COMPLETED', 199) }), 'start')
  assert.equal(metadataAction(200, { active: null, latest: run('COMPLETED', 200) }), 'complete')
})

test('reload polls an active durable job and does not duplicate it', () => {
  const active = run('RUNNING', 200)
  assert.equal(metadataAction(200, { active, latest: active }), 'poll')
  assert.equal(metadataAction(300, { active, latest: active }), 'poll')
})

test('failed pass waits for explicit retry and unsupported formats are normal completion', () => {
  assert.equal(metadataAction(200, { active: null, latest: run('FAILED', 200) }), 'failed')
  assert.equal(metadataHasIssues(run('COMPLETED', 200, {
    completedAvailable: 1, completedUnsupported: 1, failed: 0,
    staleOrUnavailable: 0, completedWithIssues: false,
  })), false)
  assert.equal(metadataHasIssues(run('COMPLETED', 200, {
    completedAvailable: 1, completedUnsupported: 0, failed: 0,
    staleOrUnavailable: 0, completedWithIssues: false,
  })), false)
  assert.equal(metadataHasIssues(run('COMPLETED', 200, {
    completedAvailable: 1, completedUnsupported: 1, failed: 1,
    staleOrUnavailable: 0, completedWithIssues: true,
  })), true)
})
