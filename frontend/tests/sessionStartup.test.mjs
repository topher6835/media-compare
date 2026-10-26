import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFileSync } from 'node:fs'
import { registerHooks } from 'node:module'
import ts from 'typescript'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { getStartupMode } from '../src/api/startup.ts'

registerHooks({ load(url, context, nextLoad) {
  if (!url.endsWith('.tsx')) return nextLoad(url, context)
  const source = ts.transpileModule(readFileSync(new URL(url), 'utf8'), {
    compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ESNext },
  }).outputText
  return { format: 'module', source, shortCircuit: true }
} })
const { SessionStartupPage } = await import('../src/session/SessionStartupPage.tsx')
const { ApplicationStartup } = await import('../src/session/ApplicationStartup.tsx')

test('management status selects startup and forwards cancellation without caching', async (t) => {
  const controller = new AbortController()
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url, '/api/management/status')
    assert.equal(options.signal, controller.signal)
    assert.equal(options.cache, 'no-store')
    return Response.json({ service: 'media-compare-management', catalogState: 'NOT_STARTED' })
  })
  assert.equal(await getStartupMode(controller.signal), 'management')
})

test('missing management endpoint preserves the direct catalog workflow', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => new Response(null, { status: 404 }))
  assert.equal(await getStartupMode(), 'catalog')
})

test('every catalog lifecycle state keeps the independent management interface available', async (t) => {
  let state
  t.mock.method(globalThis, 'fetch', async () => Response.json({
    service: 'media-compare-management', catalogState: state, processAlive: true,
  }))
  for (state of ['NOT_STARTED', 'CREATED', 'STARTING', 'RUNNING', 'STOPPING', 'FAILED']) {
    assert.equal(await getStartupMode(), 'management', state)
  }
})

test('unavailable or unexpected management responses never select catalog routes', async (t) => {
  const responses = [
    new Response(null, { status: 503 }),
    Response.json({ service: 'another-service', catalogState: 'NOT_STARTED' }),
    Response.json({ service: 'media-compare-management', catalogState: 'UNKNOWN' }),
    Response.json(null),
    new Response('<html>not JSON</html>'),
  ]
  t.mock.method(globalThis, 'fetch', async () => responses.shift())
  for (let index = 0; index < 5; index++) await assert.rejects(getStartupMode())
})

test('startup explains unavailable actions and never offers catalog navigation', () => {
  const html = renderToStaticMarkup(createElement(SessionStartupPage))
  assert.match(html, /New Session/)
  assert.match(html, /Open Existing Session/)
  assert.match(html, /Recent Sessions/)
  assert.match(html, /Session controls are coming/)
  assert.equal((html.match(/disabled=""/g) ?? []).length, 2)
  assert.doesNotMatch(html, /href=|onClick=|<form/)
})

test('catalog content is not rendered before service identification', () => {
  const html = renderToStaticMarkup(createElement(MemoryRouter, null,
    createElement(ApplicationStartup, null, createElement('p', null, 'Catalog content'))))
  assert.match(html, /Connecting to Media Compare/)
  assert.doesNotMatch(html, /Catalog content/)
})
