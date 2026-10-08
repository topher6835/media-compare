import assert from 'node:assert/strict'
import { test } from 'node:test'
import { canAnalyzeSource, sourceAuthorityWindows, releaseSourceAuthority, releaseAuthorityMessage, getSources } from '../src/api/sources.ts'
import { startIndexingRun, rememberPendingIndexingStart, getPendingIndexingStart, clearPendingIndexingStart } from '../src/api/indexing.ts'
import { startMediaMetadataRun, getMediaMetadataRun } from '../src/api/mediaMetadata.ts'
import { associatedMetadataJob, rememberAssociatedMetadataJob } from '../src/sources/metadataWorkflow.ts'
import { scheduleMediaLibraryThumbnails } from '../src/api/mediaLibrary.ts'
import { newThumbnailWork, applyScheduleStatus, visibleSchedulingCandidates, reconcileThumbnailWork } from '../src/library/mediaLibraryState.ts'

const uuid = '63a98b21-8b66-4eb2-bc4e-dc42138b9a84'
const source = { id: 12, preparationState: 'READY', filesystemProfile: 'EXFAT', liveAuthorityAvailable: false, liveAuthorityWindowId: null }

test('READY configuration after restart needs Prepare; native Sources need no windows', () => {
  assert.equal(canAnalyzeSource(source), false)
  assert.throws(() => sourceAuthorityWindows(source), /Prepare/)
  assert.equal(canAnalyzeSource({ ...source, liveAuthorityAvailable: true }), false)
  const live = { ...source, liveAuthorityAvailable: true, liveAuthorityWindowId: uuid }
  assert.deepEqual(sourceAuthorityWindows(live), [{ sourceId: 12, windowId: uuid }])
  assert.equal(canAnalyzeSource({ ...live, preparationState: 'REBIND_REQUIRED' }), false)
  for (const filesystemProfile of ['APFS', 'NTFS']) {
    assert.deepEqual(sourceAuthorityWindows({ ...source, filesystemProfile }), [])
  }
})

test('exact Source/windows are sent to indexing and retained for uncertain request replay', async () => {
  const previous = globalThis.fetch
  let body
  globalThis.fetch = async (_url, options) => { body = JSON.parse(options.body); return Response.json({ scanRunId: 3 }) }
  try {
    const windows = [{ sourceId: 12, windowId: uuid }]
    rememberPendingIndexingStart({ sourceId: 12, requestKey: uuid, authorityWindows: windows })
    await startIndexingRun(uuid, 12, getPendingIndexingStart().authorityWindows)
    assert.deepEqual(body, { requestKey: uuid, sourceIds: [12], authorityWindows: windows })
    clearPendingIndexingStart(uuid)
  } finally { globalThis.fetch = previous }
})

test('Source re-fetch reads current projection; exact release displays DRAINING and other-volume work', async () => {
  const previous = globalThis.fetch
  const requests = []
  globalThis.fetch = async (url, options) => {
    requests.push({ url, options })
    return Response.json(url === '/api/sources' ? [source] : { source, releaseState: 'DRAINING', otherWindowsOnVolumeRemain: true })
  }
  try {
    assert.equal(canAnalyzeSource((await getSources())[0]), false)
    const result = await releaseSourceAuthority(12, uuid)
    assert.deepEqual(JSON.parse(requests[1].options.body), { windowId: uuid })
    assert.match(releaseAuthorityMessage(result), /^DRAINING/)
    assert.match(releaseAuthorityMessage(result), /Other authority windows/)
    assert.match(releaseAuthorityMessage({ ...result, releaseState: 'RELEASED' }), /^RELEASED/)
  } finally { globalThis.fetch = previous }
})

test('handoff follows returned Job ID and fresh standalone windows use a separate request shape', async () => {
  const previous = globalThis.fetch
  const requests = []
  globalThis.fetch = async (url, options) => { requests.push({ url, options }); return Response.json({ jobId: 88, status: 'RUNNING' }) }
  try {
    assert.equal(associatedMetadataJob(321), undefined)
    const associated = await startMediaMetadataRun({ indexingScanRunId: 321 })
    rememberAssociatedMetadataJob(321, associated.jobId)
    await getMediaMetadataRun(associatedMetadataJob(321))
    assert.equal(requests[1].url, '/api/media-metadata-runs/88')
    assert.deepEqual(JSON.parse(requests[0].options.body), { indexingScanRunId: 321 })
    await startMediaMetadataRun({ authorityWindows: [{ sourceId: 12, windowId: uuid }] })
    assert.deepEqual(JSON.parse(requests[2].options.body), { authorityWindows: [{ sourceId: 12, windowId: uuid }] })
    await startMediaMetadataRun()
    assert.equal(requests[3].options.body, undefined)
  } finally { globalThis.fetch = previous }
})

test('finite preview request carries exact authority and cannot split ownership into repeated batches', async () => {
  const previous = globalThis.fetch
  let body
  globalThis.fetch = async (_url, options) => { body = JSON.parse(options.body); return Response.json({ results: [{ fileEntryId: 4, status: 'AUTHORITY_UNAVAILABLE' }] }) }
  try {
    const authorityWindows = [{ sourceId: 12, windowId: uuid }]
    await scheduleMediaLibraryThumbnails([4], undefined, authorityWindows)
    assert.deepEqual(body, { fileEntryIds: [4], authorityWindows })
    await assert.rejects(scheduleMediaLibraryThumbnails(Array.from({ length: 101 }, (_, i) => i + 1), undefined, authorityWindows), /100/)
  } finally { globalThis.fetch = previous }
})

test('authority unavailable stays terminal across clocks; published cache needs no authority', () => {
  const item = { fileEntryId: 4, contentRecordId: 9, generationSupport: 'SUPPORTED', thumbnail: { state: 'MISSING', url: null } }
  const unavailable = applyScheduleStatus(newThumbnailWork(item, 'generation', 0), 'AUTHORITY_UNAVAILABLE', 1)
  assert.equal(unavailable.phase, 'authority-unavailable')
  const work = reconcileThumbnailWork([item], { 4: unavailable }, 999999)
  assert.equal(work[4].phase, 'authority-unavailable')
  assert.deepEqual(visibleSchedulingCandidates([item], new Set([4]), work, 999999), [])
  const cached = { ...item, thumbnail: { state: 'PUBLISHED', url: '/api/previews/cached' } }
  assert.deepEqual(visibleSchedulingCandidates([cached], new Set([4]), {}, 0), [])
  assert.deepEqual(reconcileThumbnailWork([cached], work, 999999), {})
})

// Exercise the same explicit-retry admission function used by the thumbnail hook.
const { scheduleExplicitThumbnailRetry } = await import('../src/library/useVisibleThumbnails.ts')

test('explicit exFAT preview retry fetches the current Source/window despite physical-action capability', async () => {
  const previous = globalThis.fetch
  const requests = []
  let currentWindow = uuid
  globalThis.fetch = async (url, options) => {
    requests.push({ url, options })
    if (url === '/api/sources') return Response.json([{ ...source, liveAuthorityAvailable: true, liveAuthorityWindowId: currentWindow }])
    assert.equal(url, '/api/media-library/thumbnails')
    return Response.json({ results: [{ fileEntryId: 4, status: 'QUEUED' }] })
  }
  try {
    const item = { fileEntryId: 4, sourceId: 12, physicalActionsAvailable: true, physicalActionsUnavailableReason: null }
    const signal = new AbortController().signal
    assert.equal(await scheduleExplicitThumbnailRetry(item, signal), 'QUEUED')
    assert.equal(requests[0].url, '/api/sources')
    assert.equal(requests[0].options.signal, signal)
    const admitted = JSON.parse(requests[1].options.body)
    assert.deepEqual(admitted, { fileEntryIds: [4], authorityWindows: [{ sourceId: 12, windowId: uuid }] })
    currentWindow = 'a28a1223-75fb-41dc-bbc4-67e297b47a91'
    await scheduleExplicitThumbnailRetry(item, signal)
    assert.equal(requests[2].url, '/api/sources')
    assert.deepEqual(JSON.parse(requests[3].options.body).authorityWindows, [{ sourceId: 12, windowId: currentWindow }])
    assert.equal(admitted.authorityWindows[0].windowId, uuid) // Earlier admission never changes.
    assert.equal(requests.length, 4) // No implicit Prepare or replacement-window retry.
  } finally { globalThis.fetch = previous }
})

test('physical-action reason alone never gives a native preview retry exFAT authority', async () => {
  const previous = globalThis.fetch
  const requests = []
  let profile = 'NTFS'
  globalThis.fetch = async (url, options) => {
    requests.push({ url, options })
    if (url === '/api/sources') return Response.json([{ ...source, filesystemProfile: profile }])
    assert.equal(url, '/api/media-library/thumbnails')
    return Response.json({ results: [{ fileEntryId: 4, status: 'QUEUED' }] })
  }
  try {
    const item = { fileEntryId: 4, sourceId: 12, physicalActionsAvailable: false, physicalActionsUnavailableReason: 'AUTHORITY_UNAVAILABLE' }
    const signal = new AbortController().signal
    for (profile of ['NTFS', 'APFS']) {
      assert.equal(await scheduleExplicitThumbnailRetry(item, signal), 'QUEUED')
      assert.deepEqual(JSON.parse(requests.at(-1).options.body), { fileEntryIds: [4] })
    }
    assert.deepEqual(requests.map((request) => request.url), ['/api/sources', '/api/media-library/thumbnails', '/api/sources', '/api/media-library/thumbnails'])
  } finally { globalThis.fetch = previous }
})

test('exFAT preview retry without live authority or exact UUID does not schedule or Prepare', async () => {
  const previous = globalThis.fetch
  let projection = source
  const requests = []
  globalThis.fetch = async (url) => {
    requests.push(url)
    assert.equal(url, '/api/sources')
    return Response.json([projection])
  }
  try {
    const item = { fileEntryId: 4, sourceId: 12 }
    for (projection of [source, { ...source, liveAuthorityAvailable: true }, { ...source, liveAuthorityWindowId: uuid }]) {
      await assert.rejects(scheduleExplicitThumbnailRetry(item, new AbortController().signal), /Prepare\/Accept/)
    }
    assert.deepEqual(requests, ['/api/sources', '/api/sources', '/api/sources'])
    const entry = applyScheduleStatus(newThumbnailWork({ ...item, contentRecordId: 9, thumbnail: { state: 'MISSING', url: null } }, 'generation', 0), 'AUTHORITY_UNAVAILABLE', 1)
    assert.equal(entry.phase, 'authority-unavailable')
    assert.deepEqual(visibleSchedulingCandidates([{ ...item, contentRecordId: 9, generationSupport: 'SUPPORTED', thumbnail: { state: 'MISSING', url: null } }], new Set([4]), { 4: entry }, 99999), [])
  } finally { globalThis.fetch = previous }
})

test('finite exFAT queue rejection needs user action while native retry keeps its queue backoff', async () => {
  const previous = globalThis.fetch
  let profile = 'EXFAT'
  globalThis.fetch = async (url) => url === '/api/sources'
    ? Response.json([{ ...source, filesystemProfile: profile, liveAuthorityAvailable: true, liveAuthorityWindowId: uuid }])
    : Response.json({ results: [{ fileEntryId: 4, status: 'QUEUE_FULL' }] })
  try {
    const item = { fileEntryId: 4, sourceId: 12 }
    const signal = new AbortController().signal
    assert.equal(await scheduleExplicitThumbnailRetry(item, signal), 'AUTHORITY_UNAVAILABLE')
    profile = 'NTFS'
    assert.equal(await scheduleExplicitThumbnailRetry(item, signal), 'QUEUE_FULL')
  } finally { globalThis.fetch = previous }
})
