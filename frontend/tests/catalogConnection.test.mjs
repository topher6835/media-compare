import assert from 'node:assert/strict'
import { test } from 'node:test'
import { attachCatalog, catalogAssetUrl, catalogFetch, currentCatalogId, setManagementAccess } from '../src/api/connection.ts'

test('direct catalog development keeps relative requests and preview URLs', async (t) => {
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    assert.equal(url, '/api/health')
    assert.equal(new Headers(init?.headers).get('X-Media-Compare-Access'), null)
    assert.equal(new Headers(init?.headers).get('X-Media-Compare-Catalog'), null)
    return new Response('ok')
  })
  assert.equal(await (await catalogFetch('/api/health')).text(), 'ok')
  assert.equal(catalogAssetUrl('/api/previews/example'), '/api/previews/example')
})

test('managed requests and previews pin one generation and reject silent Session replacement', async (t) => {
  setManagementAccess('capability')
  await assert.rejects(catalogFetch('/api/sources'), /No managed catalog/)
  attachCatalog('first-generation')
  const sent = []
  t.mock.method(globalThis, 'fetch', async (url, init) => { sent.push(init); return Response.json([]) })
  await catalogFetch('/api/sources', { method: 'POST', body: '{}' })
  assert.equal(sent[0].headers.get('X-Media-Compare-Access'), 'capability')
  assert.equal(sent[0].headers.get('X-Media-Compare-Catalog'), 'first-generation')
  assert.equal(catalogAssetUrl('/api/previews/example?repair=1'), '/api/previews/example?repair=1&catalogId=first-generation')
  assert.throws(() => attachCatalog('second-generation'), /Session changed/)
  assert.equal(currentCatalogId(), 'first-generation')
  attachCatalog('first-generation') // Refresh/poll of the same child is safe.
})

test('refresh into a new launch discards old catalog URLs when session storage is available', async () => {
  const isolated = await import('../src/api/connection.ts?refresh-test')
  let stored = 'old-generation'
  let replaced = null
  globalThis.window = {
    sessionStorage: { getItem: () => stored, setItem: (_key, value) => { stored = value } },
    location: { pathname: '/library/items/9', search: '?from=items', replace: (path) => { replaced = path } },
  }
  try {
    assert.throws(() => isolated.attachCatalog('new-generation'), /Session changed/)
    assert.equal(replaced, '/')
    assert.equal(stored, 'new-generation')
    assert.equal(isolated.currentCatalogId(), null)
    window.location.pathname = '/'
    window.location.search = ''
    isolated.attachCatalog('new-generation')
    assert.equal(isolated.currentCatalogId(), 'new-generation')
  } finally { delete globalThis.window }
})

test('disabled browser storage conservatively discards unscoped catalog deep links', async () => {
  const isolated = await import('../src/api/connection.ts?disabled-storage-test')
  let replaced = null
  globalThis.window = {
    sessionStorage: { getItem: () => { throw new Error('Storage disabled') } },
    location: { pathname: '/duplicates/old-digest', search: '', replace: (path) => { replaced = path } },
  }
  try {
    assert.throws(() => isolated.attachCatalog('generation'), /Session changed or is unrecognized/)
    assert.equal(replaced, '/')
    assert.equal(isolated.currentCatalogId(), null)
  } finally { delete globalThis.window }
})
