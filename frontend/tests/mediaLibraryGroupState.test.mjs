import assert from 'node:assert/strict'
import { test } from 'node:test'
import { getMediaLibraryGroups } from '../src/api/mediaLibrary.ts'
import {
  appendLibraryGroupPage, flattenLibraryGroupPages, refreshLibraryGroupPage,
  formatGroupFileCount,
} from '../src/library/mediaLibraryGroupState.ts'
import {
  appendLibraryPage, groupThumbnailSegments, itemThumbnailSegments,
  visibleSchedulingCandidates, visibleAwaitingPages, reconcileThumbnailWork,
  newThumbnailWork,
} from '../src/library/mediaLibraryState.ts'

function item(id, state = 'MISSING', support = 'SUPPORTED') {
  return {
    fileEntryId: id, contentRecordId: id + 100, sourceId: 1,
    sourceName: 'Pictures', relativePath: `folder/${id}.jpg`, displayName: `${id}.jpg`,
    extensionKey: 'jpg', sizeBytes: 1000, format: 'jpeg',
    encodedWidth: 640, encodedHeight: 480, sourceCount: 1,
    generationSupport: support,
    thumbnail: state === 'MISSING'
      ? { state, assetKey: null, url: null, width: null, height: null }
      : { state, assetKey: 'a'.repeat(64), url: '/api/previews/' + 'a'.repeat(64), width: 320, height: 240 },
  }
}

const group = (representative, currentItemCount, groupKeyContentRecordId = representative.contentRecordId) => ({
  groupKeyContentRecordId, representative, currentItemCount,
})
const page = (groups, nextCursor = null) => ({ groups, nextCursor })

test('group API explicitly selects EXACT, pages by representative and forwards cancellation', async (t) => {
  const controller = new AbortController()
  const response = page([group(item(7), 3, 1)], 7)
  const calls = []
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push(url)
    assert.equal(init.signal, controller.signal)
    assert.equal(init.headers.get('Accept'), 'application/json')
    return Response.json(response)
  })
  assert.deepEqual(await getMediaLibraryGroups(null, controller.signal), response)
  assert.deepEqual(await getMediaLibraryGroups(7, controller.signal), response)
  assert.deepEqual(await getMediaLibraryGroups(0, controller.signal), response)
  assert.deepEqual(calls, [
    '/api/media-library/groups?relationshipType=EXACT&limit=50',
    '/api/media-library/groups?relationshipType=EXACT&limit=50&afterRepresentativeFileEntryId=7',
    '/api/media-library/groups?relationshipType=EXACT&limit=50&afterRepresentativeFileEntryId=0',
  ])
})

test('group pages retain original cursors, representative order and no duplicate cards', () => {
  let pages = appendLibraryGroupPage([], null, page([group(item(2), 3, 1), group(item(5), 1)], 5))
  pages = appendLibraryGroupPage(pages, 5, page([group(item(9), 2)], null))
  assert.deepEqual(pages.map((entry) => entry.afterRepresentativeFileEntryId), [null, 5])
  assert.deepEqual(pages.map((entry) => entry.endRepresentativeFileEntryId), [5, 9])
  assert.deepEqual(flattenLibraryGroupPages(pages).map((entry) => entry.representative.fileEntryId), [2, 5, 9])
  assert.equal(appendLibraryGroupPage(pages, 5, page([group(item(10), 1)])), pages)
  const repeat = appendLibraryGroupPage(pages, 9, page([group(item(9, 'PUBLISHED'), 2), group(item(12), 1)]))
  assert.deepEqual(flattenLibraryGroupPages(repeat).map((entry) => entry.representative.fileEntryId), [2, 5, 9, 12])
  assert.equal(flattenLibraryGroupPages(repeat)[2].representative.thumbnail.state, 'PUBLISHED')
})

test('group refresh publishes representative thumbnail without moving later groups backward', () => {
  let pages = appendLibraryGroupPage([], null, page([group(item(1), 3), group(item(2), 1)], 2))
  pages = appendLibraryGroupPage(pages, 2, page([group(item(3), 4), group(item(4), 1)], 4))
  const before = structuredClone(pages)
  const refreshed = refreshLibraryGroupPage(pages, null,
    page([group(item(1, 'PUBLISHED'), 3), group(item(3), 4)], 3))
  assert.deepEqual(refreshed[0].groups.map((entry) => entry.representative.fileEntryId), [1])
  assert.equal(refreshed[0].groups[0].representative.thumbnail.state, 'PUBLISHED')
  assert.equal(refreshed[0].nextCursor, 2)
  assert.equal(refreshed[0].endRepresentativeFileEntryId, 2)
  assert.equal(refreshed[1], pages[1])
  assert.deepEqual(flattenLibraryGroupPages(refreshed).map((entry) => entry.representative.fileEntryId), [1, 3, 4])
  assert.deepEqual(pages, before)
  assert.deepEqual(refreshLibraryGroupPage(pages, 99, page([])), pages)
})

test('shared thumbnail segments schedule and poll only visible group representatives', () => {
  const groupPages = appendLibraryGroupPage([], null,
    page([group(item(3), 4, 1), group(item(5, 'PUBLISHED'), 2, 2), group(item(7, 'MISSING', 'UNSUPPORTED'), 1)], 7))
  const segments = groupThumbnailSegments(groupPages)
  assert.deepEqual(segments.map((segment) => segment.cursor), [null])
  assert.deepEqual(segments[0].items.map((entry) => entry.fileEntryId), [3, 5, 7])
  assert.deepEqual(visibleSchedulingCandidates(segments[0].items, new Set([3, 5, 7, 9]), {}, 1000), [3])
  const work = { 3: { ...newThumbnailWork(item(3), 'generation', 1000), phase: 'awaiting' } }
  assert.deepEqual(visibleAwaitingPages(segments, new Set([3]), work), segments)
  assert.deepEqual(visibleAwaitingPages(segments, new Set([5]), work), [])
  const published = refreshLibraryGroupPage(groupPages, null,
    page([group(item(3, 'PUBLISHED'), 4, 1), group(item(5, 'PUBLISHED'), 2, 2)], 7))
  assert.deepEqual(reconcileThumbnailWork(groupThumbnailSegments(published)[0].items, work, 2000), {})
  assert.deepEqual(visibleAwaitingPages(groupThumbnailSegments(published), new Set([3]), work), [])
})

test('shared thumbnail segments preserve item-page behavior and target relevant group pages', () => {
  const itemPages = appendLibraryPage([], null, { items: [item(1), item(2)], nextCursor: 2 })
  const items = itemThumbnailSegments(itemPages)
  assert.deepEqual(items.map((entry) => entry.cursor), [null])
  assert.deepEqual(items[0].items, itemPages[0].items)
  let groups = appendLibraryGroupPage([], null, page([group(item(1), 2)], 1))
  groups = appendLibraryGroupPage(groups, 1, page([group(item(3), 1)], 3))
  const work = { 3: { ...newThumbnailWork(item(3), 'generation', 1000), phase: 'awaiting' } }
  const segments = groupThumbnailSegments(groups)
  assert.deepEqual(visibleAwaitingPages(segments, new Set([3]), work), [segments[1]])
})

test('group count text uses physical-file wording for one and many', () => {
  assert.equal(formatGroupFileCount(1), '1 file')
  assert.equal(formatGroupFileCount(2), '2 files')
})
