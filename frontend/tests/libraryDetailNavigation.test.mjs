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
})

test('exact item detail lists current, missing, and unavailable paths', () => {
  const { MediaLibraryItemDetailContent } = components[1]
  const item = { ...heic, exactSet: { digestHex: 'a'.repeat(64), physicalCopyCount: 2 } }
  const occurrence = (id, path, status, absolutePath) => ({
    fileEntryId: id, contentRecordId: id, membershipId: id, sourceId: 2,
    sourceName: 'Photos', relativePath: path, presenceStatus: status,
    applicabilityStatus: 'ACTIVE', absolutePath,
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
