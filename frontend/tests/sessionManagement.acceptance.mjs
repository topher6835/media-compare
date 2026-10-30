// Opt-in macOS integration acceptance. Requires an existing build, JAR, Chrome and Python 3.
// Run from the repository root with MEDIA_COMPARE_CHROME set to the Chrome executable.
import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { mkdtemp, readFile, realpath, cp, writeFile, readdir } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

assert.equal(process.platform, 'darwin', 'This harness qualifies macOS only; use the Windows checklist separately.')
assert.ok(process.env.MEDIA_COMPARE_CHROME, 'Set MEDIA_COMPARE_CHROME to an installed Chrome executable.')
const repo = fileURLToPath(new URL('../../', import.meta.url))
const scratch = await realpath(await mkdtemp(join(tmpdir(), 'media-compare-stage4-task4-')))
console.log('Fixtures retained at:', scratch)
const jar = join(scratch, 'Application with spaces.jar')
await cp(join(repo, 'backend/target/media-compare-0.0.1-SNAPSHOT.jar'), jar)
const children = [], logs = new Map(), pending = new Map(), posts = [], sourceRequests = [], errors = []
let ws, sequence = 0, base, capability, blockDiscovery = false

function start(label, command, args, stdin = 'ignore') {
  const env = { ...process.env }
  delete env.MEDIA_COMPARE_SESSION_ROOT
  const child = spawn(command, args, { cwd: scratch, env, stdio: [stdin, 'pipe', 'pipe'] })
  logs.set(label, '')
  for (const stream of [child.stdout, child.stderr]) stream.on('data', data => logs.set(label, logs.get(label) + data))
  child.on('error', error => logs.set(label, logs.get(label) + error))
  children.push(child)
  return child
}
async function waitFor(check, label) {
  const deadline = Date.now() + 30000
  while (Date.now() < deadline) {
    try { const value = await check(); if (value) return value } catch { /* Navigation can replace the execution context. */ }
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  throw new Error('Timed out: ' + label)
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
  await evaluate(`(() => { const b = [...document.querySelectorAll('button')].find(b => b.textContent === ${JSON.stringify(label)}); if (!b || b.disabled) throw Error('Unavailable button'); b.click() })()`)
}
async function enabled(label) {
  return evaluate(`[...document.querySelectorAll('button')].some(b => b.textContent === ${JSON.stringify(label)} && !b.disabled)`)
}
async function input(id, value) {
  await evaluate(`(() => { const i = document.getElementById(${JSON.stringify(id)}); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(i, ${JSON.stringify(value)}); i.dispatchEvent(new Event('input', {bubbles:true})) })()`)
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
  const body = await response.json()
  return { status: response.status, body }
}
async function manager(label) {
  const child = start(label, 'java', ['-jar', jar, 'manage', join(repo, 'frontend/dist'), '0', '--no-browser'])
  const url = await waitFor(() => logs.get(label).match(/Media Compare management: (http:\/\/\S+)/)?.[1], label)
  return { child, url }
}
async function stop(child) {
  if (child.exitCode !== null || child.signalCode !== null) return
  const exited = new Promise(resolve => child.once('exit', resolve))
  child.kill('SIGTERM')
  await Promise.race([exited, new Promise(resolve => setTimeout(resolve, 20000))])
  assert.ok(child.exitCode !== null || child.signalCode !== null, 'Acceptance child must actually exit; no force kill or fixture cleanup.')
}
async function openThroughUi(path) {
  await waitFor(() => enabled('Open Existing Session'), 'Open enabled')
  await click('Open Existing Session')
  await pathEntry(path)
  await waitFor(() => evaluate(`document.body.innerText.includes('Session structure is valid')`), 'valid Session')
  await click('Open this Session')
  await waitFor(() => evaluate(`!!document.querySelector('a[href="/sources"]')`), 'catalog attached')
}

try {
  let management = await manager('management')
  base = management.url
  capability = (await fetch(base + 'api/management/bootstrap', { headers: { 'X-Media-Compare-Bootstrap': '1' } }).then(r => r.json())).capability
  start('chrome', process.env.MEDIA_COMPARE_CHROME, ['--headless=new', '--disable-gpu', '--no-first-run',
    '--no-default-browser-check', '--remote-debugging-port=0', '--user-data-dir=' + join(scratch, 'chrome'), 'about:blank'])
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
      // Only discovery is fault-injected; status, Open, child startup and catalog APIs remain real.
      void (blockDiscovery ? send('Fetch.fulfillRequest', { requestId: message.params.requestId, responseCode: 503,
        responseHeaders: [{name:'Content-Type', value:'application/json'}],
        body: Buffer.from(JSON.stringify({code:'RESULT_UNAVAILABLE'})).toString('base64') })
        : send('Fetch.continueRequest', {requestId: message.params.requestId})).catch(error => errors.push(String(error)))
    }
  })
  await send('Page.enable'); await send('Runtime.enable'); await send('Network.enable')
  await send('Page.navigate', { url: base })
  await waitFor(() => enabled('New Session'), 'New enabled')
  await click('New Session')
  await waitFor(() => evaluate(`!!document.getElementById('folder-path')`), 'browser')
  await pathEntry(scratch)
  await waitFor(() => evaluate(`document.querySelector('.folder-current-path')?.textContent === ${JSON.stringify(scratch)}`), 'parent')
  await click('Select folder')
  await waitFor(() => evaluate(`!!document.getElementById('session-name')`), 'name input')
  const name = 'Café 日本 + # %', session = join(scratch, name + ' (001)')
  await writeFile(join(scratch, name), 'unrelated original bytes')
  await writeFile(join(scratch, 'original.jpg'), 'test original media sentinel')
  await input('session-name', name)

  // Acquire a real POSIX record lock after manifest publication and before Spring ownership.
  const locker = start('lock', 'python3', ['-c', `import fcntl, os, sys, time
root = sys.argv[1]
deadline = time.monotonic() + 30
while not os.path.isfile(os.path.join(root, 'session.json')):
    if time.monotonic() >= deadline: raise RuntimeError('manifest not published')
    time.sleep(0.001)
with open(os.path.join(root, 'catalog.db.lock'), 'a') as lock:
    fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    print('LOCKED', flush=True)
    sys.stdin.read(1)
`, session], 'pipe')
  await evaluate(`(() => { const b=[...document.querySelectorAll('button')].find(b=>b.textContent==='Create Session'); b.click(); b.click() })()`)
  await waitFor(() => logs.get('lock').includes('LOCKED'), 'test owns catalog lock')
  const creation = await waitFor(async () => (await api('/api/management/session-creations')).body.operations.find(op => op.kind === 'CREATE'), 'creation discovery')
  const rootPath = '/api/management/session-creations/' + creation.operationId
  await waitFor(async () => (await api(rootPath)).body.state === 'OPENING', 'real startup before refresh')
  assert.equal(sourceRequests.length, 0, 'Catalog routes must remain unmounted during initialization')
  await send('Page.reload')
  await waitFor(() => enabled('Retry Opening'), 'failed catalog actually exits')
  const failed = (await api(rootPath)).body
  assert.equal(failed.state, 'OPEN_FAILED')
  assert.equal(failed.openingFailureCode, 'OWNERSHIP_FAILED')
  assert.equal(failed.processAlive, false)
  assert.equal(failed.path, session)
  assert.equal(sourceRequests.length, 0)
  assert.deepEqual(JSON.parse(await readFile(join(session, 'session.json'), 'utf8')), {type:'media-compare-session', formatVersion:1})
  assert.equal(posts.filter(p => p.url.includes('/session-creations')).length, 2, 'Double click and refresh must not resubmit')
  locker.stdin.end('x')
  await waitFor(() => locker.exitCode === 0, 'test releases lock')
  await click('Retry Opening')
  await waitFor(async () => (await api(rootPath)).body.latestAttemptId !== creation.operationId, 'retry admitted')
  await send('Page.reload')
  await waitFor(() => evaluate(`location.pathname === '/sources' && !!document.querySelector('a[href="/library"]')`), 'retry reaches Sources')
  const root = (await api(rootPath)).body, retry = (await api('/api/management/session-creations/' + root.latestAttemptId)).body
  const status = (await api('/api/management/status')).body
  assert.equal(retry.state, 'RUNNING'); assert.equal(retry.processAlive, true)
  assert.equal(retry.catalogId, status.catalogId); assert.equal(status.catalogState, 'RUNNING')
  assert.notEqual(retry.catalogId, failed.catalogId)
  assert.equal(await readFile(join(scratch, name), 'utf8'), 'unrelated original bytes')
  assert.equal(await readFile(join(scratch, 'original.jpg'), 'utf8'), 'test original media sentinel')
  assert.equal((await readdir(scratch)).filter(n => n.startsWith(name)).length, 2, 'No replacement Session')
  assert.equal(posts.filter(p => p.url.includes('/session-creations')).length, 4)
  console.log('PASS real packaged UI: collision with file, duplicate click, manifest, real lock failure, refresh during startup and retry, actual exit, explicit Retry Opening, same Session, exact live generation and Sources readiness.')

  // Concurrent delivery against the actual HTTP server must replay, without another folder/launch.
  const replay = await Promise.all(Array.from({length:8}, () => api(rootPath, 'POST', {parentPath:scratch, name})))
  assert.ok(replay.every(r => r.status === 202 && r.body.operationId === creation.operationId && r.body.catalogId === failed.catalogId))
  const stale = await api(rootPath + '/retry-open', 'POST', {failedAttemptId:creation.operationId})
  assert.equal(stale.status, 409)
  assert.equal((await api('/api/management/status')).body.catalogId, retry.catalogId)
  const source = await evaluate(`(async () => {
    const b=await fetch('/api/management/bootstrap',{headers:{'X-Media-Compare-Bootstrap':'1'}}).then(r=>r.json());
    const r=await fetch('/api/sources',{method:'POST',headers:{'Content-Type':'application/json','X-Media-Compare-Access':b.capability,'X-Media-Compare-Catalog':${JSON.stringify(retry.catalogId)}},body:JSON.stringify({name:'Preserved Source',rootPath:${JSON.stringify(join(scratch, 'originals'))}})});
    if(r.status!==201) throw Error('Source registration failed'); return r.json(); })()`)
  await writeFile(join(session, 'notes.txt'), 'unknown Session entry stays')
  await stop(management.child)
  const inspect = spawnSync('python3', ['-c', `import json, pathlib, sqlite3, sys
c=sqlite3.connect(pathlib.Path(sys.argv[1]).as_uri()+'?mode=ro', uri=True)
assert c.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
assert not c.execute('PRAGMA foreign_key_check').fetchall()
assert c.execute('SELECT count(*) FROM flyway_schema_history WHERE success=1').fetchone()[0]==10
print(json.dumps(c.execute('SELECT id,name,root_path FROM source').fetchall()))`, join(session, 'catalog.db')], {encoding:'utf8'})
  assert.equal(inspect.status, 0, inspect.stderr)
  assert.equal(JSON.parse(inspect.stdout)[0][0], source.id)
  console.log('PASS real SQLite: all 10 migrations, integrity/foreign keys, retained Source row; management and child shut down normally.')

  management = await manager('reopened')
  base = management.url
  capability = (await fetch(base + 'api/management/bootstrap', {headers:{'X-Media-Compare-Bootstrap':'1'}}).then(r=>r.json())).capability
  const oldHandle = await api(rootPath, 'POST', {parentPath:scratch, name})
  assert.equal(oldHandle.status, 409); assert.equal(oldHandle.body.code, 'MANAGEMENT_RUNTIME_CHANGED')
  await send('Page.navigate', {url:base})
  await openThroughUi(session)
  assert.equal(await evaluate('location.pathname'), '/')
  const reopenedStatus = (await api('/api/management/status')).body
  assert.notEqual(reopenedStatus.catalogId, retry.catalogId)
  const sources = await fetch(base + 'api/sources', {headers:{'X-Media-Compare-Access':capability,'X-Media-Compare-Catalog':reopenedStatus.catalogId}}).then(r=>r.json())
  assert.ok(sources.some(s => s.id === source.id && s.name === source.name && s.rootPath === source.rootPath))
  await evaluate(`document.querySelector('a[href="/library"]').click()`)
  await send('Page.reload')
  await waitFor(() => evaluate(`location.pathname === '/library' && !!document.querySelector('a[href="/sources"]')`), 'existing deep-link refresh')
  assert.equal(await readFile(join(session, 'notes.txt'), 'utf8'), 'unknown Session entry stays')
  console.log('PASS real Open Existing: catalog row and unknown file preserved, new generation, home route, Library refresh; restarted management rejects old handles without replay.')

  const childList = spawnSync('pgrep', ['-P', String(management.child.pid)], {encoding:'utf8'})
  assert.equal(childList.status, 0, childList.stderr)
  const childPids = childList.stdout.trim().split(/\s+/).map(Number)
  assert.equal(childPids.length, 1, 'Identify only this disposable manager\'s catalog child')
  const childCommand = spawnSync('ps', ['-p', String(childPids[0]), '-o', 'command='], {encoding:'utf8'})
  assert.ok(childCommand.stdout.includes(jar) && childCommand.stdout.includes('catalog-child'))
  process.kill(childPids[0], 'SIGTERM')
  await waitFor(async () => {
    const s = (await api('/api/management/status')).body
    return s.catalogState === 'FAILED' && !s.processAlive
  }, 'actual unexpected catalog exit')
  await waitFor(() => evaluate(`document.body.innerText.includes('The selected catalog stopped or changed') && !document.querySelector('a[href="/sources"]')`), 'old document detaches catalog routes')
  const unavailable = await fetch(base + 'api/sources', {headers:{'X-Media-Compare-Access':capability,'X-Media-Compare-Catalog':reopenedStatus.catalogId}})
  assert.equal(unavailable.status, 503)
  console.log('PASS real child exit after readiness: FAILED/dead observed, old document unmounts catalog routes, forwarding rejects dead launch; no automatic creation/opening replay.')

  // The documented fallback uses real backend Open while only discovery responses are simulated.
  await stop(management.child)
  management = await manager('manual-recovery')
  base = management.url
  blockDiscovery = true
  await send('Fetch.enable', {patterns:[{urlPattern:'*/api/management/session-creations', requestStage:'Request'}]})
  await send('Page.navigate', {url:base})
  await waitFor(() => enabled('I understand; use manual Open'), 'recovery acknowledgment')
  assert.equal(await enabled('Open Existing Session'), false)
  await click('I understand; use manual Open')
  assert.equal(await enabled('New Session'), false)
  const postsBefore = posts.length
  await openThroughUi(session)
  assert.equal(await evaluate('location.pathname'), '/')
  const manualPosts = posts.slice(postsBefore)
  assert.equal(manualPosts.length, 1); assert.ok(manualPosts[0].url.endsWith('/api/management/open'))
  console.log('PASS simulated discovery outage with real management/child/catalog: acknowledged manual Open, New disabled, exact returned launch attaches, no creation POST or New-only navigation.')
  assert.equal(errors.length, 0, JSON.stringify(errors))
} catch (error) {
  console.error(error)
  throw error
} finally {
  ws?.close()
  for (const item of pending.values()) clearTimeout(item.timeout)
  try {
    for (const child of children.reverse()) await stop(child)
  } finally {
    // Keep all fixtures/logs; do not delete directories, lock files, databases or original sentinels.
    for (const [label, log] of logs) await writeFile(join(scratch, label + '.log'), log)
  }
}
