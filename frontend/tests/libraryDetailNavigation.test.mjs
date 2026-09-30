import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
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

function render(element) {
  return renderToStaticMarkup(createElement(MemoryRouter, null, element))
}

test('card target opens item detail and exact badge is a separate link', () => {
  const { MediaLibraryCard } = components[0]
  const html = render(createElement(MediaLibraryCard, {
    item: { ...heic, exactSet: { digestHex: 'a'.repeat(64), physicalCopyCount: 2 } },
    observeCard: () => () => {}, onRetry: () => {}, onRepairLoaded: () => {},
  }))
  assert.match(html, /class="library-card-main-link"[^>]*href="\/library\/items\/7"/)
  assert.match(html, /class="exact-badge"[^>]*href="\/duplicates\/a{64}"/)
  assert.match(html, /Exact ×2/)
  assert.doesNotMatch(html, /<a[^>]+><a/)
  const singleton = render(createElement(MediaLibraryCard, {
    item: heic, observeCard: () => () => {}, onRetry: () => {}, onRepairLoaded: () => {},
  }))
  assert.doesNotMatch(singleton, /exact-badge/)
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
})

test('duplicate preview keeps unsupported and non-image groups visible', () => {
  const { DuplicateRepresentativePreview } = components[2]
  assert.match(render(createElement(DuplicateRepresentativePreview, { representative: heic })), /Preview unavailable/)
  assert.match(render(createElement(DuplicateRepresentativePreview, { representative: null })), /No image preview/)
})
