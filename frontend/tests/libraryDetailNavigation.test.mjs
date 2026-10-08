import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { readFileSync } from 'node:fs'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { createServer } from 'vite'

const testsDirectory = dirname(fileURLToPath(import.meta.url))
let vite
let components

before(async () => {
  vite = await createServer({ root: join(testsDirectory, '..'), configFile: false,
    server: { middlewareMode: true, hmr: false },
    environments: { client: { dev: { hmr: false } } }, appType: 'custom' })
  components = await Promise.all([
    '/src/library/MediaLibraryCard.tsx', '/src/library/MediaLibraryItemDetailPage.tsx',
    '/src/duplicates/DuplicateRepresentativePreview.tsx',
    '/src/library/MediaLibraryPage.tsx', '/src/library/libraryView.ts',
    '/src/duplicates/DuplicatesPage.tsx', '/src/duplicates/DuplicateDetailPage.tsx',
    '/src/library/librarySession.ts', '/src/api/mediaLibrary.ts',
    '/src/library/revealStatus.ts', '/src/api/http.ts',
  ].map((name) => vite.ssrLoadModule(name)))
})

after(async () => {
  await vite?.close()
})

const heic = {
  fileEntryId: 7, contentRecordId: 4, sourceId: 2, sourceName: 'Photos',
  relativePath: 'trip/picture.heic', displayName: 'picture.heic', extensionKey: 'heic',
  sizeBytes: 123, format: null, encodedWidth: null, encodedHeight: null,
  sourceCount: 1, generationSupport: 'UNSUPPORTED',
  thumbnail: { state: 'MISSING', assetKey: null, url: null, width: null, height: null },
  absolutePath: '/media/trip/picture.heic', exactSet: null,
  physicalActionsAvailable: true, physicalActionsUnavailableReason: null,
}

function render(element, initialEntry = '/') {
  return renderToStaticMarkup(createElement(MemoryRouter, { initialEntries: [initialEntry] }, element))
}

test('card target opens item detail and exact badge is a separate link', () => {
  const { MediaLibraryCard } = components[0]
  const html = render(createElement(MediaLibraryCard, {
    item: { ...heic, exactSet: { digestHex: 'a'.repeat(64), physicalCopyCount: 2 } },
    mode: 'items',
    observeCard: () => () => {}, onRetry: () => {}, onRepairLoaded: () => {},
  }))
  assert.match(html, /class="library-card-main-link"[^>]*href="\/library\/items\/7\?from=items"/)
  assert.match(html, /class="exact-badge"[^>]*href="\/duplicates\/a{64}"/)
  assert.match(html, /Exact ×2/)
  assert.doesNotMatch(html, /<a[^>]+><a/)
  const singleton = render(createElement(MediaLibraryCard, {
    item: heic, mode: 'groups', observeCard: () => () => {}, onRetry: () => {}, onRepairLoaded: () => {},
  }))
  assert.match(singleton, /href="\/library\/items\/7\?from=groups"/)
  assert.doesNotMatch(singleton, /exact-badge/)
})

test('Library URL selects groups and safely defaults to items', () => {
  const { MediaLibraryPage } = components[3]
  const { libraryView } = components[4]
  assert.equal(libraryView(null), 'items')
  assert.equal(libraryView('unknown'), 'items')
  assert.equal(libraryView('groups'), 'groups')
  assert.match(render(createElement(MediaLibraryPage), '/library'), /aria-pressed="true">Items/)
  assert.match(render(createElement(MediaLibraryPage), '/library?view=groups'), /aria-pressed="true">Groups/)
  assert.match(render(createElement(MediaLibraryPage), '/library?view=invalid'), /aria-pressed="true">Items/)
})

test('Item Detail return link follows explicit Library context', () => {
  const { MediaLibraryItemDetailPage } = components[1]
  const route = createElement(Routes, null,
    createElement(Route, { path: '/library/items/:fileEntryId', element: createElement(MediaLibraryItemDetailPage) }))
  assert.match(render(route, '/library/items/7?from=groups'), /href="\/library\?view=groups"/)
  assert.match(render(route, '/library/items/7?from=items'), /href="\/library\?view=items"/)
  assert.match(render(route, '/library/items/7'), /href="\/library\?view=items"/)
})

test('Library snapshots restore later pages and originating cards for both modes', () => {
  const { rememberLibraryPages, rememberLibraryOrigin, getLibrarySnapshot,
    restoreLibraryPosition, libraryReturnKey } = components[7]
  const itemPage = (id, cursor) => ({ items: [{ fileEntryId: id }], nextCursor: id,
    afterFileEntryId: cursor, endFileEntryId: id })
  rememberLibraryPages('items', 'items-key', [itemPage(7, null), itemPage(57, 7)])
  rememberLibraryOrigin('items', 'items-key', 57, 1200)
  const items = getLibrarySnapshot('items', 'items-key')
  assert.equal(items.pages.length, 2)
  assert.deepEqual(restoreLibraryPosition([7, 57], items), { cardId: 57, scrollY: 1200 })
  assert.deepEqual(restoreLibraryPosition([7], items), { cardId: null, scrollY: 0 })
  assert.equal(getLibrarySnapshot('groups', 'items-key'), null)
  const groupPage = { groups: [{ representative: { fileEntryId: 73 } }], nextCursor: 73,
    afterRepresentativeFileEntryId: null, endRepresentativeFileEntryId: 73 }
  rememberLibraryPages('groups', 'groups-key', [groupPage])
  rememberLibraryOrigin('groups', 'groups-key', 73, 900)
  assert.deepEqual(restoreLibraryPosition([73], getLibrarySnapshot('groups', 'groups-key')),
    { cardId: 73, scrollY: 900 })
  assert.equal(restoreLibraryPosition([], getLibrarySnapshot('items', 'new-visit')), null)
  assert.equal(libraryReturnKey({ restoreLibraryKey: 'items-key' }, 'new-key'), 'items-key')
  assert.equal(libraryReturnKey(null, 'browser-back-key'), 'browser-back-key')
})

test('HEIC detail shows path and honest unavailable metadata and preview', () => {
  const { MediaLibraryItemDetailContent } = components[1]
  const html = render(createElement(MediaLibraryItemDetailContent,
    { item: heic, brokenPreview: false, onPreviewError: () => {} }))
  assert.match(html, /picture\.heic/)
  assert.match(html, /Preview unavailable for this format/)
  assert.match(html, /\/media\/trip\/picture\.heic/)
  assert.match(html, /Decoded format<\/dt><dd>Unavailable/)
  assert.doesNotMatch(html, /<img/)
  assert.doesNotMatch(html, /Exact copies/)
  assert.equal((html.match(/Reveal in Finder/g) ?? []).length, 1)
})

test('exact item detail lists current, missing, and unavailable paths', () => {
  const { MediaLibraryItemDetailContent } = components[1]
  const item = { ...heic, exactSet: { digestHex: 'a'.repeat(64), physicalCopyCount: 2 } }
  const occurrence = (id, path, status, absolutePath) => ({
    fileEntryId: id, contentRecordId: id, membershipId: id, sourceId: 2,
    sourceName: 'Photos', relativePath: path, presenceStatus: status,
    applicabilityStatus: 'ACTIVE', absolutePath, physicalActionsAvailable: true, physicalActionsUnavailableReason: null,
  })
  const exactDetail = { digestHex: item.exactSet.digestHex, occurrences: [
    occurrence(7, 'trip/picture.heic', 'PRESENT', '/media/trip/picture.heic'),
    occurrence(8, 'backup/picture.heic', 'PRESENT', null),
    occurrence(9, 'old/picture.heic', 'MISSING', '/media/old/picture.heic'),
  ] }
  const html = render(createElement(MediaLibraryItemDetailContent,
    { item, exactDetail, brokenPreview: false, onPreviewError: () => {} }))
  assert.match(html, /Exact copies · 2 present/)
  assert.match(html, /CURRENT/)
  assert.match(html, /FileEntry #8/)
  assert.match(html, /Full path unavailable from current trusted catalog route/)
  assert.match(html, /MISSING/)
  assert.match(html, /\/media\/old\/picture\.heic/)
  assert.equal((html.match(/Reveal in Finder/g) ?? []).length, 2)
})

test('duplicate preview keeps unsupported and non-image groups visible', () => {
  const { DuplicateRepresentativePreview } = components[2]
  assert.match(render(createElement(DuplicateRepresentativePreview, { representative: heic })), /Preview unavailable/)
  assert.match(render(createElement(DuplicateRepresentativePreview, { representative: null })), /No image preview/)
  const supported = { ...heic, generationSupport: 'SUPPORTED',
    thumbnail: { state: 'PUBLISHED', assetKey: 'key', url: '/api/previews/key', width: 120, height: 80 } }
  assert.match(render(createElement(DuplicateRepresentativePreview, { representative: supported })), /<img[^>]+src="\/api\/previews\/key"/)
})

test('exact list uses compact representative beside unchanged summary counts', () => {
  const { DuplicateGroupList } = components[5]
  const group = {
    digestHex: 'b'.repeat(64), sizeBytes: 123, contentRecordCount: 2,
    presentOccurrenceCount: 2, missingOccurrenceCount: 0, sourceCount: 1,
    potentialStorageSavingsBytes: 123, filterMatch: null, representative: heic,
  }
  const html = render(createElement(DuplicateGroupList, { groups: [group], filterSearch: '' }))
  const css = readFileSync(join(testsDirectory, '../src/index.css'), 'utf8')
  assert.match(html, /class="duplicate-card"[^>]*><div class="duplicate-representative"/)
  assert.match(html, /class="duplicate-card-content"/)
  assert.match(html, /Exact members<\/dt><dd>2/)
  assert.match(css, /\.duplicate-card\s*\{[^}]*grid-template-columns:\s*184px minmax\(0, 1fr\)/)
  assert.match(css, /\.duplicate-representative\s*\{[^}]*height:\s*132px/)
  assert.match(css, /\.duplicate-representative img\s*\{[^}]*object-fit:\s*cover/)
})

test('exact detail shows one representative and no member thumbnails', () => {
  const { DuplicateGroupDetail } = components[6]
  const detail = {
    digestHex: 'c'.repeat(64), sizeBytes: 123, contentRecordCount: 2,
    presentOccurrenceCount: 2, missingOccurrenceCount: 0, sourceCount: 1,
    potentialStorageSavingsBytes: 123, representative: heic,
    members: [{ contentRecordId: 7, sizeBytes: 123 }, { contentRecordId: 8, sizeBytes: 123 }],
    occurrences: [],
  }
  const html = render(createElement(DuplicateGroupDetail, {
    detail, filters: { fileCategories: [], extensions: [] },
  }))
  assert.equal((html.match(/class="duplicate-representative"/g) ?? []).length, 1)
  assert.match(html, /Preview unavailable/)
  assert.match(html, /Physical copies/)
})

test('exact detail offers one reveal action per present physical copy', () => {
  const { DuplicateGroupDetail } = components[6]
  const occurrence = (fileEntryId, membershipId, path) => ({
    fileEntryId, membershipId, contentRecordId: fileEntryId,
    sourceId: membershipId, sourceName: `Source ${membershipId}`,
    relativePath: 'picture.heic', absolutePath: path,
    presenceStatus: 'PRESENT', applicabilityStatus: 'ACTIVE', extension: 'HEIC',
    fileCategory: 'PHOTO', matchesFilter: false,
  })
  const detail = {
    digestHex: 'd'.repeat(64), sizeBytes: 123, contentRecordCount: 2,
    presentOccurrenceCount: 2, missingOccurrenceCount: 0, sourceCount: 2,
    potentialStorageSavingsBytes: 123, representative: null,
    members: [], occurrences: [
      occurrence(7, 1, '/media/picture.heic'),
      occurrence(7, 2, '/media/picture.heic'),
      occurrence(8, 3, '/media/copy.heic'),
    ],
  }
  const html = render(createElement(DuplicateGroupDetail, {
    detail, filters: { fileCategories: [], extensions: [] },
  }))
  assert.equal((html.match(/Reveal in Finder<\/button>/g) ?? []).length, 2)
  assert.match(html, /aria-label="Reveal FileEntry 7 in Finder"/)
  assert.match(html, /aria-label="Reveal FileEntry 8 in Finder"/)
})

test('reveal API sends only a physical FileEntry ID and no browser path', async () => {
  const { revealMediaLibraryFile } = components[8]
  const originalFetch = globalThis.fetch
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push([url, options])
    return { ok: true, status: 204 }
  }
  try {
    await revealMediaLibraryFile(7)
    assert.deepEqual(calls, [['/api/media-library/items/7/reveal', {
      method: 'POST', headers: { 'X-Media-Compare-Reveal': '1' }, signal: undefined,
    }]])
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('reveal failures distinguish stale file, platform, and request errors', () => {
  const { revealFailureMessage } = components[9]
  const { ApiError } = components[10]
  assert.match(revealFailureMessage(new ApiError(409)), /current location could not be verified/)
  assert.match(revealFailureMessage(new ApiError(410)), /current location could not be verified/)
  assert.match(revealFailureMessage(new ApiError(501)), /macOS only/)
  assert.match(revealFailureMessage(new Error('offline')), /backend is running/)
})
