import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFileSync } from 'node:fs'
import { registerHooks } from 'node:module'
import ts from 'typescript'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { getStartupMode, getManagementStatus, openSession, catalogFailureMessage } from '../src/api/startup.ts'
import { getDirectories, validateSession } from '../src/api/filesystem.ts'

registerHooks({ load(url, context, nextLoad) {
  if (!url.endsWith('.tsx')) return nextLoad(url, context)
  const source = ts.transpileModule(readFileSync(new URL(url), 'utf8'), {
    compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ESNext },
  }).outputText
  return { format: 'module', source, shortCircuit: true }
} })
const { SessionStartupPage } = await import('../src/session/SessionStartupPage.tsx')
const { ApplicationStartup } = await import('../src/session/ApplicationStartup.tsx')
const { FolderBrowser } = await import('../src/session/FolderBrowser.tsx')
const capability = 'a'.repeat(43)
const baseStatus = { service: 'media-compare-management', catalogState: 'NOT_STARTED', processAlive: false, failureCode: null, exitCode: null, catalogId: null }

test('missing bootstrap endpoint preserves direct catalog development', async (t) => {
  t.mock.method(globalThis, 'fetch', async (url) => url === '/api/management/bootstrap'
    ? new Response(null, { status: 404 })
    : Response.json({ runtimeId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' }))
  assert.equal(await getStartupMode(), 'catalog')
})

test('bootstrap acquires a capability without credentials or caching and supports cancellation', async (t) => {
  const controller = new AbortController()
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url, '/api/management/bootstrap')
    assert.equal(options.headers['X-Media-Compare-Bootstrap'], '1')
    assert.equal(options.signal, controller.signal)
    assert.equal(options.cache, 'no-store')
    return Response.json({ service: baseStatus.service, capability })
  })
  assert.equal(await getStartupMode(controller.signal), 'management')
})

test('status accepts lifecycle states and sends capability; ready state requires a live generation', async (t) => {
  let status = baseStatus
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url, '/api/management/status')
    assert.equal(options.headers.get('X-Media-Compare-Access'), capability)
    return Response.json(status)
  })
  for (const state of ['NOT_STARTED', 'CREATED', 'STARTING', 'RUNNING', 'STOPPING', 'FAILED']) {
    status = { ...baseStatus, catalogState: state, processAlive: true, catalogId: 'launch-one' }
    assert.equal((await getManagementStatus()).catalogState, state)
  }
  for (const invalid of [{ ...baseStatus, catalogState: 'RUNNING' }, { ...baseStatus, catalogState: 'UNKNOWN' }, null]) {
    status = invalid
    await assert.rejects(getManagementStatus())
  }
})

test('invalid bootstrap and unavailable management never select catalog routes', async (t) => {
  const responses = [new Response(null, { status: 503 }), Response.json(null), Response.json({ service: 'other', capability }), Response.json({ service: baseStatus.service, capability: 'short' })]
  t.mock.method(globalThis, 'fetch', async () => responses.shift())
  for (let i = 0; i < 4; i++) await assert.rejects(getStartupMode())
})

test('browsing and validation encode host paths independently; opening uses an authenticated POST', async (t) => {
  const path = 'C:\\Family café 日本 + # %\\My Session'
  const requests = []
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    requests.push({ url, options })
    return Response.json(baseStatus)
  })
  await getDirectories(path)
  await validateSession(path)
  await openSession(path)
  for (const request of requests.slice(0, 2)) {
    assert.equal(new URL(request.url, 'http://local').searchParams.get('path'), path)
    assert.equal(request.options.headers.get('X-Media-Compare-Access'), capability)
  }
  assert.match(requests[0].url, /filesystem\/directories/)
  assert.match(requests[1].url, /filesystem\/validate/)
  assert.equal(requests[2].url, '/api/management/open')
  assert.equal(requests[2].options.method, 'POST')
  assert.equal(requests[2].options.body, path)
  assert.equal(requests[2].options.headers.get('Content-Type'), 'text/plain;charset=UTF-8')
})

test('isolated startup without management recovery keeps New disabled and catalog routes unmounted', () => {
  const html = renderToStaticMarkup(createElement(SessionStartupPage))
  assert.match(html, /Open Existing Session/)
  assert.match(html, /Recent Sessions/)
  assert.equal((html.match(/disabled=""/g) ?? []).length, 1)
  assert.doesNotMatch(html, /href=/)
  const startup = renderToStaticMarkup(createElement(MemoryRouter, null,
    createElement(ApplicationStartup, null, createElement('p', null, 'Catalog content'))))
  assert.match(startup, /Connecting to Media Compare/)
  assert.doesNotMatch(startup, /Catalog content/)
})

test('initializing and failed-live states explain progress and prohibit another open', () => {
  const html = renderToStaticMarkup(createElement(SessionStartupPage, { status: { ...baseStatus, catalogState: 'STARTING', processAlive: true } }))
  assert.match(html, /Opening Session/)
  assert.equal((html.match(/disabled=""/g) ?? []).length, 2)
  const failed = renderToStaticMarkup(createElement(SessionStartupPage, { status: { ...baseStatus, catalogState: 'FAILED', processAlive: true, failureCode: 'SHUTDOWN_TIMEOUT' } }))
  assert.match(failed, /fully exit/)
  assert.equal((failed.match(/disabled=""/g) ?? []).length, 2)
  assert.match(catalogFailureMessage('OWNERSHIP_FAILED'), /already be open/)
  assert.match(catalogFailureMessage('INITIALIZATION_FAILED'), /damaged or incompatible/)
})

test('reusable folder browser has Open and New modes without creating Sessions', () => {
  const html = renderToStaticMarkup(createElement(FolderBrowser, { mode: 'open', onSelect() {}, onCancel() {} }))
  assert.match(html, /Absolute folder path/)
  assert.match(html, /Refresh locations/)
  assert.match(html, /Open this Session/)
  assert.doesNotMatch(html, /Folder creation/)
  const future = renderToStaticMarkup(createElement(FolderBrowser, { mode: 'new', onSelect() {}, onCancel() {} }))
  assert.match(future, /Select an existing parent folder/)
})
