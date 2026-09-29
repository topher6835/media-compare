import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  appendLibraryPage, flattenLibraryPages, refreshLibraryPage,
  visibleSchedulingCandidates, visibleRepairCandidates, visibleAwaitingPages,
  newThumbnailWork, applyScheduleStatus, reconcileThumbnailWork,
  THUMBNAIL_RETRY_MS, THUMBNAIL_WAIT_MS,
} from '../src/library/mediaLibraryState.ts'
import {
  chunkThumbnailIds, getMediaLibraryItems, scheduleMediaLibraryThumbnails,
} from '../src/api/mediaLibrary.ts'

function item(id, state = 'MISSING', support = 'SUPPORTED') {
  return {
    fileEntryId: id, contentRecordId: id + 100, sourceId: 1,
    sourceName: 'Pictures', relativePath: `folder/${id}.jpg`, displayName: `${id}.jpg`,
    extensionKey: 'JPG', sizeBytes: 1000, format: 'jpeg',
    encodedWidth: 640, encodedHeight: 480, sourceCount: 1,
    generationSupport: support,
    thumbnail: state === 'MISSING'
      ? { state, assetKey: null, url: null, width: null, height: null }
      : { state, assetKey: 'a'.repeat(64), url: '/api/previews/' + 'a'.repeat(64), width: 320, height: 240 },
  }
}

const page = (items, nextCursor = null) => ({ items, nextCursor })
const waiting = (entry, phase = 'awaiting', purpose = 'generation') => ({
  ...newThumbnailWork(entry, purpose, 1000), phase,
})

test('appending keyset pages preserves FileEntry order and stores fetch cursors', () => {
  let pages = appendLibraryPage([], null, page([item(2), item(5)], 5))
  pages = appendLibraryPage(pages, 5, page([item(8), item(10)]))
  assert.deepEqual(flattenLibraryPages(pages).map((entry) => entry.fileEntryId), [2, 5, 8, 10])
  assert.deepEqual(pages.map((entry) => entry.afterFileEntryId), [null, 5])
  assert.deepEqual(pages.map((entry) => entry.endFileEntryId), [5, 10])
})

test('repeated pages and repeated FileEntries never duplicate cards', () => {
  const pages = appendLibraryPage([], null, page([item(1), item(2)], 2))
  assert.equal(appendLibraryPage(pages, null, page([item(1)])), pages)
  const withRepeat = appendLibraryPage(pages, 2, page([item(2, 'PUBLISHED'), item(3)]))
  assert.deepEqual(flattenLibraryPages(withRepeat).map((entry) => entry.fileEntryId), [1, 2, 3])
  assert.equal(flattenLibraryPages(withRepeat)[1].thumbnail.state, 'PUBLISHED')
})

test('page refresh replaces MISSING with PUBLISHED without mutation or duplicates', () => {
  const pages = appendLibraryPage([], null, page([item(1), item(2)], 2))
  const before = structuredClone(pages)
  const next = refreshLibraryPage(pages, null, page([item(1, 'PUBLISHED'), item(2)], 2))
  assert.equal(flattenLibraryPages(next)[0].thumbnail.state, 'PUBLISHED')
  assert.equal(flattenLibraryPages(next).length, 2)
  assert.deepEqual(pages, before)
})

test('eligibility changes stay within original page boundaries and preserve later cursors', () => {
  let pages = appendLibraryPage([], null, page([item(1), item(2)], 2))
  pages = appendLibraryPage(pages, 2, page([item(3), item(4)]))
  const next = refreshLibraryPage(pages, null, page([item(2, 'PUBLISHED'), item(3)], 3))
  assert.deepEqual(flattenLibraryPages(next).map((entry) => entry.fileEntryId), [2, 3, 4])
  assert.equal(next[0].nextCursor, 2)
  assert.equal(next[1], pages[1])
  assert.deepEqual(refreshLibraryPage(pages, 99, page([])), pages)
})

test('scheduling includes only visible MISSING SUPPORTED items in catalog order', () => {
  const items = [item(1), item(2), item(3, 'PUBLISHED'), item(4, 'MISSING', 'UNSUPPORTED'), item(5)]
  assert.deepEqual(visibleSchedulingCandidates(items, new Set([5, 4, 3, 1]), {}, 1000), [1, 5])
  assert.deepEqual(visibleSchedulingCandidates(items, new Set(), {}, 1000), [])
})

test('in-flight, awaiting, failed and paused work is not schedule eligible', () => {
  for (const phase of ['scheduling', 'awaiting', 'failed', 'paused']) {
    assert.deepEqual(visibleSchedulingCandidates([item(1)], new Set([1]), { 1: waiting(item(1), phase) }, 2000), [])
  }
})

test('queue-full waits two seconds and retries only while visible before the deadline', () => {
  const entry = item(1)
  const deferred = applyScheduleStatus(waiting(entry, 'scheduling'), 'QUEUE_FULL', 1000)
  const work = { 1: deferred }
  assert.equal(deferred.phase, 'deferred')
  assert.deepEqual(visibleSchedulingCandidates([entry], new Set([1]), work, 1000 + THUMBNAIL_RETRY_MS - 1), [])
  assert.deepEqual(visibleSchedulingCandidates([entry], new Set([1]), work, 1000 + THUMBNAIL_RETRY_MS), [1])
  assert.deepEqual(visibleSchedulingCandidates([entry], new Set(), work, 5000), [])
  assert.deepEqual(visibleSchedulingCandidates([entry], new Set([1]), work, 1000 + THUMBNAIL_WAIT_MS), [])
})

test('QUEUED and ALREADY_QUEUED both await publication', () => {
  for (const status of ['QUEUED', 'ALREADY_QUEUED']) {
    assert.equal(applyScheduleStatus(waiting(item(1), 'scheduling'), status, 2000).phase, 'awaiting')
  }
})

test('publication releases client work and relevant visible-page polling', () => {
  const entry = item(1)
  const work = { 1: waiting(entry) }
  const pages = appendLibraryPage([], null, page([entry]))
  assert.deepEqual(visibleAwaitingPages(pages, new Set([1]), work), pages)
  const publishedPages = refreshLibraryPage(pages, null, page([item(1, 'PUBLISHED')]))
  assert.deepEqual(reconcileThumbnailWork(flattenLibraryPages(publishedPages), work, 2000), {})
  assert.deepEqual(visibleAwaitingPages(publishedPages, new Set([1]), work), [])
})

test('polling selects only segments with visible awaiting generation work', () => {
  let pages = appendLibraryPage([], null, page([item(1)], 1))
  pages = appendLibraryPage(pages, 1, page([item(2)], 2))
  pages = appendLibraryPage(pages, 2, page([item(3, 'PUBLISHED')]))
  const work = { 1: waiting(item(1)), 2: waiting(item(2), 'deferred'), 3: waiting(item(3, 'PUBLISHED'), 'awaiting', 'repair') }
  assert.deepEqual(visibleAwaitingPages(pages, new Set([2, 3]), work), [])
  assert.deepEqual(visibleAwaitingPages(pages, new Set([1, 2, 3]), work), [pages[0]])
})

test('deadline pauses stuck work and explicit retry starts a fresh bounded window', () => {
  const entry = item(1)
  const paused = reconcileThumbnailWork([entry], { 1: waiting(entry) }, 1000 + THUMBNAIL_WAIT_MS)
  assert.equal(paused[1].phase, 'paused')
  assert.deepEqual(visibleAwaitingPages(appendLibraryPage([], null, page([entry])), new Set([1]), paused), [])
  const retry = { 1: newThumbnailWork(entry, 'generation', 40000) }
  assert.deepEqual(visibleSchedulingCandidates([entry], new Set([1]), retry, 40000), [1])
})

test('removed, changed-content and unsupported items release obsolete work', () => {
  const work = { 1: waiting(item(1)), 2: waiting(item(2)), 3: waiting(item(3)) }
  const items = [{ ...item(2), contentRecordId: 999 }, item(3, 'MISSING', 'UNSUPPORTED')]
  assert.deepEqual(reconcileThumbnailWork(items, work, 2000), {})
  assert.deepEqual(visibleSchedulingCandidates(items, new Set([2, 3]), work, 2000), [])
})

test('published repairs require explicit state, support, visibility and bounded backoff', () => {
  const entry = item(1, 'PUBLISHED')
  assert.deepEqual(visibleRepairCandidates([entry], new Set([1]), {}, 1000), [])
  const repair = { 1: newThumbnailWork(entry, 'repair', 1000) }
  assert.deepEqual(visibleRepairCandidates([entry], new Set([1]), repair, 1000), [1])
  assert.deepEqual(visibleRepairCandidates([entry], new Set(), repair, 1000), [])
  assert.deepEqual(visibleRepairCandidates([item(1, 'PUBLISHED', 'UNSUPPORTED')], new Set([1]), repair, 1000), [])
  repair[1] = applyScheduleStatus(repair[1], 'QUEUE_FULL', 1000)
  assert.deepEqual(visibleRepairCandidates([entry], new Set([1]), repair, 2000), [])
  assert.deepEqual(visibleRepairCandidates([entry], new Set([1]), repair, 3000), [1])
})

test('repair tracking retains published metadata and releases when its URL changes', () => {
  const entry = item(1, 'PUBLISHED')
  const work = { 1: waiting(entry, 'awaiting', 'repair') }
  assert.equal(reconcileThumbnailWork([entry], work, 2000)[1], work[1])
  assert.deepEqual(reconcileThumbnailWork([{ ...entry, thumbnail: { ...entry.thumbnail, url: '/api/previews/new' } }], work, 2000), {})
})

test('request chunks preserve order, cap at 100 and do not mutate IDs', () => {
  const ids = Array.from({ length: 205 }, (_, index) => index + 1)
  const chunks = chunkThumbnailIds(ids)
  assert.deepEqual(chunks.map((chunk) => chunk.length), [100, 100, 5])
  assert.deepEqual(chunks.flat(), ids)
  assert.deepEqual(chunkThumbnailIds([]), [])
  assert.deepEqual(chunkThumbnailIds(ids.slice(0, 100)).map((chunk) => chunk.length), [100])
})

test('item API requests explicit 50-item pages and forwards cancellation', async (t) => {
  const controller = new AbortController()
  const calls = []
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push(url)
    assert.equal(init.signal, controller.signal)
    assert.equal(init.headers.get('Accept'), 'application/json')
    return Response.json(page([]))
  })
  await getMediaLibraryItems(null, controller.signal)
  await getMediaLibraryItems(42, controller.signal)
  await getMediaLibraryItems(0, controller.signal)
  assert.deepEqual(calls, ['/api/media-library/items?limit=50', '/api/media-library/items?limit=50&afterFileEntryId=42', '/api/media-library/items?limit=50&afterFileEntryId=0'])
})

test('scheduler API sends ordered bounded POSTs and returns every admission result', async (t) => {
  const ids = Array.from({ length: 205 }, (_, index) => 205 - index)
  const controller = new AbortController()
  const batches = []
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    assert.equal(url, '/api/media-library/thumbnails')
    assert.equal(init.method, 'POST')
    assert.equal(init.signal, controller.signal)
    assert.equal(init.headers.get('Content-Type'), 'application/json')
    const body = JSON.parse(init.body)
    assert.deepEqual(Object.keys(body), ['fileEntryIds'])
    batches.push(body.fileEntryIds)
    return Response.json({ results: body.fileEntryIds.map((fileEntryId) => ({ fileEntryId, status: 'QUEUED' })) }, { status: 202 })
  })
  const response = await scheduleMediaLibraryThumbnails(ids, controller.signal)
  assert.deepEqual(batches.map((batch) => batch.length), [100, 100, 5])
  assert.deepEqual(batches.flat(), ids)
  assert.deepEqual(response.results.map((result) => result.fileEntryId), ids)
  await scheduleMediaLibraryThumbnails([], controller.signal)
  assert.equal(batches.length, 3)
})

test('HTTP failures and cancellation stop scheduling subsequent batches', async (t) => {
  const ids = Array.from({ length: 101 }, (_, index) => index + 1)
  let calls = 0
  const mock = t.mock.method(globalThis, 'fetch', async () => {
    calls++
    return new Response(null, { status: 503 })
  })
  await assert.rejects(scheduleMediaLibraryThumbnails(ids), /status 503/)
  assert.equal(calls, 1)
  mock.mock.mockImplementation(async () => { throw new DOMException('Aborted', 'AbortError') })
  await assert.rejects(scheduleMediaLibraryThumbnails(ids), { name: 'AbortError' })
})
