// Opt-in actual Windows acceptance. Build/package first; no additional npm dependencies.
// Requires MEDIA_COMPARE_CHROME and the compiled WindowsManagementAcceptanceDriver test helper.
import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { mkdtemp, readFile, realpath, cp, writeFile, readdir, mkdir, rename } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

assert.equal(process.platform, 'win32', 'Native Windows only; no platform simulation.')
assert.ok(process.env.MEDIA_COMPARE_CHROME, 'Set MEDIA_COMPARE_CHROME to an installed Chrome executable.')
const repo = fileURLToPath(new URL('../../', import.meta.url))
const scratch = await realpath(await mkdtemp(join(tmpdir(), 'media-compare-stage4-task4-windows-')))
console.log('Fixtures retained at:', scratch)
const jar = join(scratch, 'Application with spaces.jar')
await cp(join(repo, 'backend/target/media-compare-0.0.1-SNAPSHOT.jar'), jar)
const driverName = 'io.github.topher6835.mediacompare.management.WindowsManagementAcceptanceDriver'
const driverPath = 'io/github/topher6835/mediacompare/management/WindowsManagementAcceptanceDriver.class'
const helpers = join(scratch, 'helpers')
await mkdir(join(helpers, 'io/github/topher6835/mediacompare/management'), { recursive: true })
await cp(join(repo, 'backend/target/test-classes', driverPath), join(helpers, driverPath))
const children = [], logs = new Map(), pending = new Map(), posts = [], sourceRequests = [], errors = []
let ws, sequence = 0, base, capability, blockDiscovery = false, loseCreationResponse = false, lostResponses = 0
const driverArgs = ['-Dloader.main=' + driverName, '-Dloader.path=' + helpers, '-cp', jar,
  'org.springframework.boot.loader.launch.PropertiesLauncher']

function start(label, command, args, stdin = 'pipe') {
  const env = { ...process.env }
  delete env.MEDIA_COMPARE_SESSION_ROOT
  const child = spawn(command, args, { cwd: scratch, env, windowsHide: true, stdio: [stdin, 'pipe', 'pipe'] })
  logs.set(label, '')
  for (const stream of [child.stdout, child.stderr]) stream.on('data', data => logs.set(label, logs.get(label) + data))
  child.on('error', error => logs.set(label, logs.get(label) + error))
  children.push({ label, child })
  console.log('Test-owned process:', label, 'pid=', child.pid)
  return child
}
async function waitFor(check, label, budget = 40000) {
  const deadline = Date.now() + budget
  while (Date.now() < deadline) {
    try { const value = await check(); if (value) return value } catch { /* Navigation can replace CDP contexts. */ }
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  throw Error('Timed out: ' + label)
}
function send(method, params = {}) {
  const id = ++sequence
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { pending.delete(id); reject(Error('CDP timeout: ' + method)) }, 10000)
    pending.set(id, { resolve, reject, timeout })
    ws.send(JSON.stringify({ id, method, params }))
  })
}
async function evaluate(expression) {
  const response = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (response.exceptionDetails) throw Error(JSON.stringify(response.exceptionDetails))
  return response.result.value
}
async function click(label) {
  await evaluate(`(() => { const b=[...document.querySelectorAll('button')].find(b=>b.textContent===${JSON.stringify(label)}); if(!b||b.disabled)throw Error('Unavailable button'); b.click() })()`)
}
async function enabled(label) {
  return evaluate(`[...document.querySelectorAll('button')].some(b=>b.textContent===${JSON.stringify(label)}&&!b.disabled)`)
}
async function input(id, value) {
  await evaluate(`(() => { const i=document.getElementById(${JSON.stringify(id)}); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(i,${JSON.stringify(value)}); i.dispatchEvent(new Event('input',{bubbles:true})) })()`)
}
async function pathEntry(path) {
  await input('folder-path', path)
  await evaluate(`document.querySelector('.folder-path-form').requestSubmit()`)
}
async function api(path, method = 'GET', fields) {
  const headers = { 'X-Media-Compare-Access': capability }
  if (fields) headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8'
  const response = await fetch(base + path.replace(/^\//, ''), {
    method, headers, body: fields ? new URLSearchParams(fields).toString() : undefined,
  })
  return { status: response.status, body: await response.json() }
}
async function manager(label) {
  const child = start(label, 'java', [...driverArgs, 'manage', join(repo, 'frontend/dist'), '0', '--no-browser'])
  base = await waitFor(() => logs.get(label).match(/Media Compare management: (http:\/\/\S+)/)?.[1], label)
  capability = (await fetch(base + 'api/management/bootstrap', {
    headers: { 'X-Media-Compare-Bootstrap': '1' },
  }).then(r => r.json())).capability
  return child
}
async function stopOwned(child) {
  if (child.exitCode !== null) return
  child.stdin.end() // Test driver requests ordinary System.exit; product shutdown hook owns cleanup.
  await waitFor(() => child.exitCode !== null, 'ordinary owned Java process exit', 30000)
  assert.equal(child.exitCode, 0)
}
async function openThroughUi(path) {
  await waitFor(() => enabled('Open Existing Session'), 'Open enabled')
  await click('Open Existing Session')
  await pathEntry(path)
  await waitFor(() => evaluate(`document.body.innerText.includes('Session structure is valid')`), 'valid Session')
  await click('Open this Session')
  await waitFor(() => evaluate(`!!document.querySelector('a[href="/sources"]')`), 'catalog attached')
}
async function newName(name) {
  await waitFor(() => enabled('New Session'), 'New enabled')
  await click('New Session')
  await waitFor(() => evaluate(`!!document.getElementById('folder-path')`), 'parent browser')
  await pathEntry(scratch)
  await waitFor(() => evaluate(`document.querySelector('.folder-current-path')?.textContent===${JSON.stringify(scratch)}`), 'parent')
  await click('Select folder')
  await waitFor(() => evaluate(`!!document.getElementById('session-name')`), 'name field')
  await input('session-name', name)
}
function inspectSqlite(session) {
  const result = spawnSync(process.env.MEDIA_COMPARE_PYTHON || 'python', ['-c', `import json,pathlib,sqlite3,sys
with sqlite3.connect(pathlib.Path(sys.argv[1]).as_uri()+'?mode=ro',uri=True) as db:
 assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
 assert not db.execute('PRAGMA foreign_key_check').fetchall()
 assert db.execute('SELECT count(*) FROM flyway_schema_history WHERE success=1').fetchone()[0]==10
 print(json.dumps(db.execute('SELECT id,name,root_path FROM source').fetchall(),ensure_ascii=True))`, join(session, 'catalog.db')],
  { encoding: 'utf8', windowsHide: true })
  assert.equal(result.status, 0, result.stderr)
  return JSON.parse(result.stdout)
}

try {
  let management = await manager('new')
  const chrome = start('chrome', process.env.MEDIA_COMPARE_CHROME, ['--headless=new', '--disable-gpu', '--no-first-run',
    '--no-default-browser-check', '--remote-debugging-port=0', '--user-data-dir=' + join(scratch, 'chrome'), 'about:blank'], 'ignore')
  const port = await waitFor(async () => (await readFile(join(scratch, 'chrome/DevToolsActivePort'), 'utf8')).split('\n')[0], 'Chrome')
  const tabs = await fetch('http://127.0.0.1:' + port + '/json').then(r => r.json())
  ws = new WebSocket(tabs.find(tab => tab.type === 'page').webSocketDebuggerUrl)
  await new Promise(resolve => ws.addEventListener('open', resolve, { once: true }))
  ws.addEventListener('message', ({ data }) => {
    const message = JSON.parse(data), item = pending.get(message.id)
    if (item) {
      clearTimeout(item.timeout); pending.delete(message.id)
      if (message.error) item.reject(Error(JSON.stringify(message.error))); else item.resolve(message.result)
    }
    if (message.method === 'Network.requestWillBeSent') {
      const request = message.params.request
      if (request.method === 'POST') posts.push(request)
      if (request.url.includes('/api/sources')) sourceRequests.push(request)
    }
    if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails)
    if (message.method === 'Fetch.requestPaused') {
      const event = message.params
      const responseStage = event.responseStatusCode !== undefined
      // SIMULATED loss after the real creation request has executed; never resubmit it in this harness.
      if (responseStage && loseCreationResponse && event.request.method === 'POST'
          && /\/api\/management\/session-creations\/[^/]+$/.test(event.request.url)) {
        loseCreationResponse = false; lostResponses++
        void send('Fetch.failRequest', { requestId: event.requestId, errorReason: 'ConnectionClosed' }).catch(e => errors.push(String(e)))
        return
      }
      // SIMULATED discovery outage only. All actual storage, Open, child and catalog execution is native.
      void (blockDiscovery ? send('Fetch.fulfillRequest', { requestId: event.requestId, responseCode: 503,
        responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
        body: Buffer.from(JSON.stringify({ code: 'RESULT_UNAVAILABLE' })).toString('base64') })
        : send(responseStage ? 'Fetch.continueResponse' : 'Fetch.continueRequest', { requestId: event.requestId }))
        .catch(e => errors.push(String(e)))
    }
  })
  await send('Page.enable'); await send('Runtime.enable'); await send('Network.enable')
  await send('Page.navigate', { url: base })
  const name = 'Café 日本 + # %', session = join(scratch, name + ' (001)')
  await writeFile(join(scratch, name), 'original collision sentinel')
  await writeFile(join(scratch, 'original.jpg'), 'original media sentinel')
  await mkdir(join(scratch, 'originals'))
  await newName(name)
  const postsBefore = posts.length
  await evaluate(`(() => { const b=[...document.querySelectorAll('button')].find(b=>b.textContent==='Create Session'); b.click(); b.click() })()`)
  const creation = await waitFor(async () => (await api('/api/management/session-creations')).body.operations.find(op => op.kind === 'CREATE'), 'New handle')
  const rootPath = '/api/management/session-creations/' + creation.operationId
  await waitFor(async () => (await api(rootPath)).body.state === 'OPENING', 'actual New startup')
  assert.equal(sourceRequests.length, 0, 'Sources must not mount before actual readiness')
  await send('Page.reload')
  await waitFor(() => evaluate(`location.pathname==='/sources'&&!!document.querySelector('a[href="/library"]')`), 'New Sources navigation')
  const running = (await api(rootPath)).body, status = (await api('/api/management/status')).body
  assert.equal(running.state, 'RUNNING'); assert.equal(running.processAlive, true)
  assert.equal(status.catalogState, 'RUNNING'); assert.equal(status.catalogId, running.catalogId)
  assert.equal(running.path, session)
  assert.deepEqual(JSON.parse(await readFile(join(session, 'session.json'), 'utf8')), { type: 'media-compare-session', formatVersion: 1 })
  assert.equal(posts.slice(postsBefore).filter(p => p.url.includes('/session-creations')).length, 2)
  const replay = await Promise.all(Array.from({ length: 8 }, () => api(rootPath, 'POST', { parentPath: scratch, name })))
  assert.ok(replay.every(r => r.status === 202 && r.body.catalogId === running.catalogId))
  assert.equal((await readdir(scratch)).filter(n => n.startsWith(name)).length, 2)
  assert.equal(await readFile(join(scratch, name), 'utf8'), 'original collision sentinel')
  assert.equal(await readFile(join(scratch, 'original.jpg'), 'utf8'), 'original media sentinel')
  console.log('PASS native Windows New: Unicode/space/punctuation, collision preservation, duplicate click and concurrent delivery, startup refresh, manifest, exact RUNNING/alive generation and Sources navigation.')

  const sourceResponse = await fetch(base + 'api/sources', { method: 'POST', headers: {
    'Content-Type': 'application/json', 'X-Media-Compare-Access': capability, 'X-Media-Compare-Catalog': running.catalogId,
  }, body: JSON.stringify({ name: 'Preserved disposable Source', rootPath: join(scratch, 'originals') }) })
  assert.equal(sourceResponse.status, 201)
  const source = await sourceResponse.json()
  await writeFile(join(session, 'notes.txt'), 'unknown Session entry preserved')
  await send('Page.reload')
  await waitFor(() => evaluate(`location.pathname==='/sources'&&!!document.querySelector('a[href="/library"]')`), 'ready refresh')
  await stopOwned(management)
  assert.equal(inspectSqlite(session)[0][0], source.id)
  const moved = join(scratch, 'Moved Session café 日本')
  await rename(session, moved)
  for (const file of ['catalog.db', 'catalog.db.lock']) {
    await rename(join(moved, file), join(moved, file + '.rename-probe'))
    await rename(join(moved, file + '.rename-probe'), join(moved, file))
  }
  console.log('PASS native normal shutdown: manager hook and actual child exit, SQLite integrity/foreign keys/10 migrations, preserved Source, Session/database/lock rename without removing lock file.')

  management = await manager('open')
  const foreign = await api(rootPath, 'POST', { parentPath: scratch, name })
  assert.equal(foreign.status, 409); assert.equal(foreign.body.code, 'MANAGEMENT_RUNTIME_CHANGED')
  await send('Page.navigate', { url: base })
  await openThroughUi(moved)
  assert.equal(await evaluate('location.pathname'), '/')
  const reopened = (await api('/api/management/status')).body
  assert.notEqual(reopened.catalogId, running.catalogId)
  const sources = await fetch(base + 'api/sources', { headers: {
    'X-Media-Compare-Access': capability, 'X-Media-Compare-Catalog': reopened.catalogId,
  } }).then(r => r.json())
  assert.ok(sources.some(s => s.id === source.id && s.rootPath === source.rootPath))
  const staleGeneration = await fetch(base + 'api/sources', { headers: {
    'X-Media-Compare-Access': capability, 'X-Media-Compare-Catalog': running.catalogId,
  } })
  assert.equal(staleGeneration.status, 409)
  await evaluate(`document.querySelector('a[href="/library"]').click()`)
  await send('Page.reload')
  await waitFor(() => evaluate(`location.pathname==='/library'&&!!document.querySelector('a[href="/sources"]')`), 'Library refresh')
  assert.equal(await readFile(join(moved, 'notes.txt'), 'utf8'), 'unknown Session entry preserved')
  await stopOwned(management)
  console.log('PASS native Open Existing: moved Unicode Session, preserved catalog/originals, new generation, stale-generation rejection, management restart rejects old creation handle, home routing and deep-link refresh.')

  management = await manager('retry')
  await send('Page.navigate', { url: base })
  const retryName = 'Retry 日本 with spaces', retrySession = join(scratch, retryName)
  await newName(retryName)
  const encoded = 'session-path-utf8-v1:' + Buffer.from(retrySession).toString('base64url')
  const locker = start('lock', 'java', [...driverArgs, 'lock', encoded])
  const sourceCount = sourceRequests.length, retryPostCount = posts.length
  await click('Create Session')
  await waitFor(() => logs.get('lock').includes('LOCKED'), 'actual competing Windows lock')
  const failedCreation = await waitFor(async () => (await api('/api/management/session-creations')).body.operations.find(op => op.kind === 'CREATE'), 'failed creation')
  const failedRoot = '/api/management/session-creations/' + failedCreation.operationId
  await waitFor(async () => (await api(failedRoot)).body.state === 'OPENING', 'locked child opening')
  await send('Page.reload')
  await waitFor(() => enabled('Retry Opening'), 'failure and actual child exit')
  const failed = (await api(failedRoot)).body
  assert.equal(failed.openingFailureCode, 'OWNERSHIP_FAILED'); assert.equal(failed.processAlive, false)
  assert.equal(sourceRequests.length, sourceCount)
  assert.equal(failed.path, retrySession)
  await stopOwned(locker)
  await click('Retry Opening')
  await waitFor(async () => (await api(failedRoot)).body.latestAttemptId !== failedCreation.operationId, 'explicit retry admitted')
  await send('Page.reload')
  await waitFor(() => evaluate(`location.pathname==='/sources'&&!!document.querySelector('a[href="/library"]')`), 'retry Sources')
  const retryRoot = (await api(failedRoot)).body
  const attempt = (await api('/api/management/session-creations/' + retryRoot.latestAttemptId)).body
  const retryStatus = (await api('/api/management/status')).body
  assert.equal(attempt.state, 'RUNNING'); assert.equal(attempt.processAlive, true)
  assert.equal(attempt.catalogId, retryStatus.catalogId); assert.notEqual(attempt.catalogId, failed.catalogId)
  assert.equal(posts.slice(retryPostCount).filter(p => p.url.includes('/session-creations')).length, 4)
  assert.equal((await readdir(scratch)).filter(n => n.startsWith(retryName)).length, 1)
  assert.equal((await api(failedRoot + '/retry-open', 'POST', { failedAttemptId: failedCreation.operationId })).status, 409)
  await stopOwned(management)
  console.log('PASS native real lock failure: actual child exit, no early Sources, explicit Retry Opening of same Session, startup/retry refresh, new exact generation, no duplicate folder or automatic submission.')

  management = await manager('manual-recovery')
  blockDiscovery = true
  await send('Fetch.enable', { patterns: [{ urlPattern: '*/api/management/session-creations', requestStage: 'Request' }] })
  await send('Page.navigate', { url: base })
  await waitFor(() => enabled('I understand; use manual Open'), 'recovery acknowledgment')
  assert.equal(await enabled('Open Existing Session'), false)
  await click('I understand; use manual Open')
  assert.equal(await enabled('New Session'), false)
  const manualPostsBefore = posts.length
  await openThroughUi(moved)
  assert.equal(await evaluate('location.pathname'), '/')
  const manualPosts = posts.slice(manualPostsBefore)
  assert.equal(manualPosts.length, 1); assert.ok(manualPosts[0].url.endsWith('/api/management/open'))
  assert.equal(errors.length, 0, JSON.stringify(errors))
  await stopOwned(management)
  console.log('PASS SIMULATED discovery-only outage: real native management/storage/child/catalog, acknowledgment before manual Open, New disabled, one Open POST, no creation replay and correct attachment.')

  blockDiscovery = false
  await send('Fetch.disable')
  management = await manager('lost-response')
  await send('Page.navigate', { url: base })
  const lostName = 'Lost response 日本'
  await newName(lostName)
  const lostPostsBefore = posts.length
  loseCreationResponse = true
  await send('Fetch.enable', { patterns: [{ urlPattern: '*/api/management/session-creations/*', requestStage: 'Response' }] })
  await click('Create Session')
  await waitFor(() => evaluate(`location.pathname==='/sources'&&!!document.querySelector('a[href="/library"]')`), 'lost response recovers by observation')
  assert.equal(lostResponses, 1)
  assert.equal(posts.slice(lostPostsBefore).filter(p => p.url.includes('/session-creations')).length, 2)
  assert.equal((await readdir(scratch)).filter(n => n.startsWith(lostName)).length, 1)
  const recoveredOperation = (await api('/api/management/session-creations')).body.operations.find(op => op.kind === 'CREATE')
  const recovered = (await api('/api/management/session-creations/' + recoveredOperation.operationId)).body
  assert.equal(recovered.state, 'RUNNING'); assert.equal(recovered.processAlive, true)
  assert.equal(recovered.path, join(scratch, lostName))
  assert.equal(recovered.catalogId, (await api('/api/management/status')).body.catalogId)
  assert.equal(errors.length, 0, JSON.stringify(errors))
  await stopOwned(management)
  console.log('PASS SIMULATED lost creation response: actual Windows creation/child/catalog, observed retained handle, one reservation/submission only, no automatic creation replay and matching Sources attachment.')
  await send('Browser.close').catch(() => {})
  await waitFor(() => chrome.exitCode !== null, 'owned Chrome normal close')
  console.log('PASS all Windows browser scenarios; fixtures and logs retained.')
} finally {
  // Release own locks first, then ask each owned manager to run ordinary shutdown; never force-kill.
  const cleanupErrors = []
  try {
    const javaChildren = [...children.filter(p => p.label === 'lock'),
      ...[...children].reverse().filter(p => p.label !== 'chrome' && p.label !== 'lock')]
    for (const { child } of javaChildren) {
      try { await stopOwned(child) } catch (error) { cleanupErrors.push(error) }
    }
    const chrome = children.find(p => p.label === 'chrome')?.child
    if (chrome && chrome.exitCode === null) {
      try {
        assert.equal(ws?.readyState, WebSocket.OPEN, 'CDP must remain available for ordinary browser cleanup')
        await send('Browser.close').catch(() => {})
        await waitFor(() => chrome.exitCode !== null, 'owned Chrome cleanup')
      } catch (error) { cleanupErrors.push(error) }
    }
  } finally {
    ws?.close()
    for (const item of pending.values()) clearTimeout(item.timeout)
    for (const [label, log] of logs) await writeFile(join(scratch, label + '.log'), log)
  }
  if (cleanupErrors.length) throw new AggregateError(cleanupErrors, 'Test-owned process cleanup failed; fixtures/logs retained')
}
