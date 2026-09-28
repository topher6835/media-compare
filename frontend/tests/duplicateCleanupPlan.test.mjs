import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import {
  aggregateCleanupPlan,
  clearCleanupPlan,
  createCleanupPlanEntry,
  estimateCleanupSavings,
  formatCleanupSavings,
  getCleanupPlan,
  getCleanupPlanEntry,
  invalidateUnavailableCleanupKeeper,
  removeCleanupPlanEntry,
  saveCleanupPlanEntry,
} from '../src/duplicates/duplicateCleanupPlan.ts'

function occurrence(fileEntryId, membershipId, presenceStatus = 'PRESENT', extra = {}) {
  return {
    fileEntryId, membershipId, contentRecordId: fileEntryId,
    sourceId: membershipId, sourceName: `Source ${membershipId}`,
    relativePath: `folder-${membershipId}/photo.jpg`, presenceStatus,
    applicabilityStatus: 'ACTIVE', extension: 'JPG', fileCategory: 'PHOTO',
    matchesFilter: false, ...extra,
  }
}

function detail(digestHex = 'a'.repeat(64), sizeBytes = 100) {
  return {
    digestHex, sizeBytes, contentRecordCount: 3, redundantContentRecordCount: 2,
    presentOccurrenceCount: 3, missingOccurrenceCount: 0, sourceCount: 3,
    potentialStorageSavingsBytes: 200, members: [],
    occurrences: [occurrence(1, 1), occurrence(2, 2), occurrence(3, 3)],
  }
}

function entry(digestHex, keeperId = 1, sizeBytes = 100) {
  const result = createCleanupPlanEntry(detail(digestHex, sizeBytes), keeperId)
  assert.ok(result)
  return result
}

afterEach(clearCleanupPlan)

test('adds a decision keyed by digest with physical IDs and path snapshots', () => {
  const planned = entry('a')
  saveCleanupPlanEntry(planned)
  assert.deepEqual(getCleanupPlan(), [planned])
  assert.equal(getCleanupPlanEntry('a').keeper.fileEntryId, 1)
  assert.deepEqual(getCleanupPlanEntry('a').candidates.map((copy) => copy.fileEntryId), [2, 3])
})

test('replaces the same digest without moving it in insertion order', () => {
  saveCleanupPlanEntry(entry('b'))
  saveCleanupPlanEntry(entry('a'))
  saveCleanupPlanEntry(entry('b', 2))
  assert.deepEqual(getCleanupPlan().map((plan) => plan.digestHex), ['b', 'a'])
  assert.equal(getCleanupPlanEntry('b').keeper.fileEntryId, 2)
  assert.deepEqual(getCleanupPlanEntry('b').candidates.map((copy) => copy.fileEntryId), [1, 3])
})

test('removes one group and clears the complete plan', () => {
  saveCleanupPlanEntry(entry('a'))
  saveCleanupPlanEntry(entry('b'))
  removeCleanupPlanEntry('a')
  assert.equal(getCleanupPlanEntry('a'), undefined)
  assert.equal(getCleanupPlan().length, 1)
  clearCleanupPlan()
  assert.deepEqual(getCleanupPlan(), [])
})

test('copies input and output arrays and nested Source paths defensively', () => {
  const input = entry('a')
  saveCleanupPlanEntry(input)
  input.keeper.sourcePaths[0].sourceName = 'changed input'
  input.candidates[0].sourcePaths[0].relativePath = 'changed input'
  input.candidates.push(input.keeper)
  const output = getCleanupPlanEntry('a')
  output.keeper.sourcePaths[0].sourceName = 'changed output'
  output.candidates[0].sourcePaths.length = 0
  const list = getCleanupPlan()
  list[0].candidates[0].label = 'changed list'
  list.length = 0
  assert.deepEqual(getCleanupPlanEntry('a'), entry('a'))
})

test('aggregates all groups, one keeper each, physical candidates and exact savings', () => {
  saveCleanupPlanEntry(entry('a'))
  saveCleanupPlanEntry(entry('b', 2, 250))
  assert.deepEqual(aggregateCleanupPlan(getCleanupPlan()), {
    groupCount: 2, keeperCount: 2, candidateCount: 4, estimatedSavingsBytes: 700,
  })
  assert.deepEqual(aggregateCleanupPlan([]), {
    groupCount: 0, keeperCount: 0, candidateCount: 0, estimatedSavingsBytes: 0,
  })
})

test('does not double-count candidate IDs and recomputes supplied totals', () => {
  const input = entry('a')
  input.candidates.push(input.candidates[0], input.keeper)
  input.candidateCount = 999
  input.estimatedSavingsBytes = 999
  saveCleanupPlanEntry(input)
  assert.equal(getCleanupPlanEntry('a').candidateCount, 2)
  assert.equal(getCleanupPlanEntry('a').estimatedSavingsBytes, 200)
  assert.equal(aggregateCleanupPlan([entry('a'), {
    ...entry('b'), candidates: [entry('b').candidates[0], entry('b').candidates[0]],
  }]).candidateCount, 3)
})

test('fails closed for unsafe products and input sizes, including exact safe boundary', () => {
  assert.equal(estimateCleanupSavings(1, Number.MAX_SAFE_INTEGER), Number.MAX_SAFE_INTEGER)
  assert.equal(estimateCleanupSavings(2, Number.MAX_SAFE_INTEGER), null)
  for (const invalid of [Number.MAX_SAFE_INTEGER + 1, -1, 1.5, Infinity, NaN]) {
    assert.equal(estimateCleanupSavings(1, invalid), null)
    assert.equal(estimateCleanupSavings(invalid, 1), null)
  }
  assert.equal(estimateCleanupSavings(2, 0), 0)
  assert.equal(formatCleanupSavings(null), 'Estimate unavailable')
})

test('fails closed when otherwise safe group estimates overflow in aggregate', () => {
  const half = Math.floor(Number.MAX_SAFE_INTEGER / 2)
  saveCleanupPlanEntry(entry('a', 1, half))
  assert.equal(getCleanupPlanEntry('a').estimatedSavingsBytes, Number.MAX_SAFE_INTEGER - 1)
  saveCleanupPlanEntry(entry('b', 1, 1))
  assert.equal(aggregateCleanupPlan(getCleanupPlan()).estimatedSavingsBytes, null)
  saveCleanupPlanEntry(entry('a', 1, Number.MAX_SAFE_INTEGER))
  assert.equal(getCleanupPlanEntry('a').estimatedSavingsBytes, null)
  assert.equal(aggregateCleanupPlan(getCleanupPlan()).estimatedSavingsBytes, null)
})

test('overlapping memberships, filter nonmatches and mixed presence retain physical semantics', () => {
  const group = detail()
  group.occurrences.push(occurrence(2, 4, 'MISSING'), occurrence(2, 5), occurrence(4, 6, 'MISSING'))
  group.occurrences.push(occurrence(5, 7, 'PRESENT', { applicabilityStatus: 'RETIRED' }))
  const planned = createCleanupPlanEntry(group, 1)
  assert.deepEqual(planned.candidates.map((copy) => copy.fileEntryId), [2, 3])
  assert.equal(planned.candidates[0].sourcePaths.length, 3)
  assert.equal(planned.estimatedSavingsBytes, 200)
  assert.equal(createCleanupPlanEntry(group, 4), null)
  assert.equal(createCleanupPlanEntry(group, 5), null)
  group.occurrences[0].relativePath = 'changed'
  assert.equal(planned.keeper.sourcePaths[0].relativePath, 'folder-1/photo.jpg')
})

test('fresh detail removes a missing or absent saved keeper without a replacement', () => {
  for (const unavailable of ['missing', 'absent']) {
    saveCleanupPlanEntry(entry('a'))
    const fresh = detail('a')
    if (unavailable === 'missing') fresh.occurrences[0].presenceStatus = 'MISSING'
    else fresh.occurrences.shift()
    assert.equal(invalidateUnavailableCleanupKeeper(fresh), true)
    assert.equal(getCleanupPlanEntry('a'), undefined)
  }
})

test('fresh detail keeps a valid decision and does not silently rewrite its snapshot', () => {
  saveCleanupPlanEntry(entry('a'))
  const fresh = detail('a')
  fresh.occurrences[1].presenceStatus = 'MISSING'
  assert.equal(invalidateUnavailableCleanupKeeper(fresh), false)
  assert.deepEqual(getCleanupPlanEntry('a'), entry('a'))
  assert.equal(createCleanupPlanEntry(fresh, 1).candidateCount, 1)
})

test('fewer than two present copies cannot produce or retain a cleanup decision', () => {
  saveCleanupPlanEntry(entry('a'))
  const group = detail('a')
  group.occurrences = [occurrence(1, 1)]
  assert.equal(createCleanupPlanEntry(group, 1), null)
  assert.equal(invalidateUnavailableCleanupKeeper(group), true)
  group.occurrences = []
  assert.equal(createCleanupPlanEntry(group, 1), null)
})
