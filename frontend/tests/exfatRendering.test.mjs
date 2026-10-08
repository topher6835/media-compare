import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFileSync } from 'node:fs'
import { registerHooks } from 'node:module'
import ts from 'typescript'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'

// Existing Node test runner and TypeScript compiler; no additional framework.
registerHooks({ load(url, context, nextLoad) {
  if (!url.endsWith('.tsx')) return nextLoad(url, context)
  const source = ts.transpileModule(readFileSync(new URL(url), 'utf8'), {
    compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ESNext },
  }).outputText
  return { format: 'module', source, shortCircuit: true }
} })
const { SourceAuthorityControls } = await import('../src/sources/SourceAuthorityControls.tsx')
const { RevealFileButton } = await import('../src/library/RevealFileButton.tsx')
const { DuplicatePhysicalCopies } = await import('../src/duplicates/DuplicatePhysicalCopies.tsx')
const { MediaLibraryItemDetailContent } = await import('../src/library/MediaLibraryItemDetailPage.tsx')
const source = { id: 1, filesystemProfile: 'EXFAT', liveAuthorityWindowId: 'old-window' }
const render = (component, props) => renderToStaticMarkup(createElement(MemoryRouter, null, createElement(component, props)))

test('release remains available during work and DRAINING allows checking exact old authority', () => {
  const html = render(SourceAuthorityControls, { source, pending: false, onRelease() {},
    message: { windowId: 'old-window', draining: true, text: 'DRAINING: protected work is still closing.' } })
  assert.match(html, /Release authority<\/button>/)
  assert.doesNotMatch(html, /disabled=""/)
  assert.match(html, /Check release/)
  assert.match(html, /Releasing active authority may stop affected work/)
  const closed = render(SourceAuthorityControls, { source: { ...source, liveAuthorityWindowId: null }, pending: false, onRelease() {},
    message: { draining: false, text: 'RELEASED: this authority has closed.' } })
  assert.match(closed, /RELEASED/)
  assert.doesNotMatch(closed, /Check release|Release authority<\/button>/)
})

test('native Source has no release UI; historical exFAT Reveal stays disabled', () => {
  assert.equal(render(SourceAuthorityControls, { source: { ...source, filesystemProfile: 'NTFS' }, pending: false, onRelease() {} }), '')
  const exfat = render(RevealFileButton, { fileEntryId: 1, available: false })
  assert.match(exfat, /disabled=""/)
  assert.match(exfat, /historical exFAT physical actions/)
  assert.doesNotMatch(render(RevealFileButton, { fileEntryId: 1, available: true }), /disabled=""/)
})

test('cached exFAT detail loads without windows while reveal and cleanup are blocked', () => {
  const occurrence = { fileEntryId: 1, contentRecordId: 5, membershipId: 1, sourceId: 1, sourceName: 'Images', relativePath: 'photo.png',
    presenceStatus: 'PRESENT', applicabilityStatus: 'ACTIVE', absolutePath: 'C:\\Images\\photo.png', physicalActionsAvailable: false }
  const html = render(MediaLibraryItemDetailContent, { item: { ...occurrence, displayName: 'photo.png', sizeBytes: 99,
    thumbnail: { url: '/api/previews/cached.png' }, exactSet: null }, onPreviewError() {} })
  assert.match(html, /src="\/api\/previews\/cached.png"/)
  assert.match(html, /disabled=""/)
  const copies = render(DuplicatePhysicalCopies, { detail: { digestHex: 'a'.repeat(64), sizeBytes: 99,
    occurrences: [occurrence, { ...occurrence, fileEntryId: 2, contentRecordId: 6, membershipId: 2 }] }, filtersActive: false, filterSearch: '' })
  assert.match(copies, /Physical cleanup is unavailable/)
  assert.doesNotMatch(copies, /type="radio"|Add to cleanup plan/)
})

test('missing preview detail does not infer filesystem or Prepare guidance from physical-action reason', () => {
  const item = { fileEntryId: 4, sourceId: 12, sourceName: 'Pictures', displayName: 'photo.png', sizeBytes: 99,
    generationSupport: 'SUPPORTED', thumbnail: { state: 'MISSING', url: null }, exactSet: null,
    absolutePath: 'C:\\Pictures\\photo.png', physicalActionsAvailable: false,
    physicalActionsUnavailableReason: 'AUTHORITY_UNAVAILABLE' }
  const html = render(MediaLibraryItemDetailContent, { item, onPreviewError() {} })
  assert.match(html, /Preview not generated/)
  assert.doesNotMatch(html, /Prepare\/Accept|finite preview/)
  assert.match(html, /disabled=""/) // Physical-action denial still applies independently.
})

test('duplicate planning fails closed for missing or undefined physical-action capability', () => {
  const allowed = { fileEntryId: 1, contentRecordId: 5, membershipId: 1, sourceId: 1, sourceName: 'Images', relativePath: 'photo.png',
    presenceStatus: 'PRESENT', applicabilityStatus: 'ACTIVE', physicalActionsAvailable: true, absolutePath: 'C:\\Images\\photo.png' }
  for (const explicitUndefined of [false, true]) {
    const unavailable = { ...allowed, fileEntryId: 2, contentRecordId: 6, membershipId: 2 }
    if (explicitUndefined) unavailable.physicalActionsAvailable = undefined
    else delete unavailable.physicalActionsAvailable
    const html = render(DuplicatePhysicalCopies, { detail: { digestHex: 'b'.repeat(64), sizeBytes: 99, occurrences: [allowed, unavailable] }, filtersActive: false, filterSearch: '' })
    assert.doesNotMatch(html, /type="radio"|Add to cleanup plan/)
    assert.match(html, /disabled=""/) // Undefined Reveal capability also remains denied.
  }
})

test('explicitly true native physical-action capability preserves keeper selection and reveal', () => {
  const occurrence = { fileEntryId: 1, contentRecordId: 5, membershipId: 1, sourceId: 1, sourceName: 'Images', relativePath: 'photo.png',
    presenceStatus: 'PRESENT', applicabilityStatus: 'ACTIVE', absolutePath: 'C:\\Images\\photo.png', physicalActionsAvailable: true }
  const html = render(DuplicatePhysicalCopies, { detail: { digestHex: 'c'.repeat(64), sizeBytes: 99,
    occurrences: [occurrence, { ...occurrence, fileEntryId: 2, contentRecordId: 6, membershipId: 2 }] }, filtersActive: false, filterSearch: '' })
  assert.equal((html.match(/type="radio"/g) ?? []).length, 2)
  assert.equal((html.match(/Reveal in Finder<\/button>/g) ?? []).length, 2)
  assert.doesNotMatch(html, /disabled=""|Physical cleanup is unavailable/)
})
