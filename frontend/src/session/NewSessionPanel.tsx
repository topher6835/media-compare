import { useState } from 'react'
import { catalogFailureMessage, type ManagementStatus } from '../api/startup.ts'
import { creationErrorMessage, type CreationOperation } from '../api/sessionCreations.ts'
import { CreationWorkflow, type CreationView } from './creationWorkflow.ts'
import { sessionNameError, suggestedSessionName } from './sessionName.ts'
import { FolderBrowser } from './FolderBrowser.tsx'

function progress(attempt: CreationOperation): string {
  switch (attempt.state) {
    case 'RESERVED': return 'Reserved. No work starts until you explicitly submit this reservation.'
    case 'CREATING': return 'Creating Session…'
    case 'OPENING': return attempt.catalogId ? 'Catalog initialization in progress…' : 'Session created; opening catalog…'
    case 'RUNNING': return attempt.catalogMatchesCurrent && attempt.catalogState === 'RUNNING' && attempt.processAlive
      ? 'Catalog ready. Verifying the matching launch before opening Sources…'
      : 'This opening succeeded earlier, but its catalog is no longer ready. Return to startup and open the Session manually.'
    case 'REJECTED': return creationErrorMessage(attempt.errorCode)
    case 'CREATION_UNCERTAIN': return creationErrorMessage('CREATION_UNCERTAIN')
    case 'OPEN_FAILED': return 'Session created, but catalog opening failed. ' + catalogFailureMessage(attempt.openingFailureCode)
  }
}

export function NewSessionPanel({ workflow, view, status, onClose }: {
  workflow: CreationWorkflow; view: CreationView; status: ManagementStatus; onClose: () => void
}) {
  const reserved = workflow.reservedInput()
  const [parent, setParent] = useState<string | null>(reserved?.parent ?? null)
  const [name, setName] = useState(() => reserved?.name ?? suggestedSessionName())
  const attempt = view.attempt
  const naming = !attempt || attempt.kind === 'CREATE' && attempt.state === 'RESERVED'
  const invalid = sessionNameError(name)
  function close() { workflow.returnToStartup(); onClose() }
  return <section className="new-session" aria-labelledby="new-session-title" aria-busy={view.busy}>
    <h2 id="new-session-title">New Session</h2>
    {view.error && <p role="alert">{view.error}</p>}
    {view.blocked && <p>Inspect the selected parent for any Session that may have been created. Return to startup to open a valid Session manually. Creating again requires a deliberate new action.</p>}
    {attempt && <>
      <p role={['REJECTED', 'CREATION_UNCERTAIN', 'OPEN_FAILED'].includes(attempt.state) ? 'alert' : 'status'}>{progress(attempt)}</p>
      {attempt.path && <p>Created Session: <strong>{attempt.name}</strong><br /><span className="folder-current-path">{attempt.path}</span></p>}
      {attempt.attemptedPath && !attempt.sessionCreated && <p>Inspect attempted location: <span className="folder-current-path">{attempt.attemptedPath}</span></p>}
      {attempt.state === 'REJECTED' && attempt.message && <p>{attempt.message}</p>}
      {attempt.executionActive && <p role="status">The operation is still active on the server.</p>}
      {(attempt.state === 'OPEN_FAILED' || attempt.kind === 'RETRY_OPEN' && attempt.state === 'RESERVED') && <>
        {status.processAlive && <p role="status">Waiting for the previous catalog process to fully exit.</p>}
        <button type="button" disabled={attempt.state === 'RESERVED'
          ? view.busy || view.blocked || view.stopping || view.activeElsewhere || status.processAlive
          : !workflow.canRetry()} onClick={() => { void (attempt.state === 'RESERVED' ? workflow.submitReservedRetry() : workflow.retryOpening()) }}>
          {attempt.state === 'RESERVED' ? 'Submit reserved opening retry' : 'Retry Opening'}
        </button>
      </>}
    </>}
    {naming && !view.blocked && (parent === null ? <FolderBrowser mode="new" busy={!workflow.canStart()}
      onSelect={setParent} onCancel={close} /> : <form className="session-name-form" onSubmit={(event) => {
      event.preventDefault()
      if (!invalid) void workflow.create(parent, name)
    }}>
      <p>Selected parent: <strong className="folder-current-path">{parent}</strong></p>
      <p>Media Compare creates a dedicated child folder here. Existing contents stay in place. If the name is taken, a suffix such as (001) is added automatically.</p>
      <label htmlFor="session-name">Session name</label>
      <input id="session-name" value={name} onChange={(event) => setName(event.target.value)} autoComplete="off"
        spellCheck={false} disabled={view.busy || !!view.root?.parentPath} aria-invalid={!!invalid} aria-describedby="session-name-feedback" />
      <p id="session-name-feedback" role="status">{invalid ?? 'Names can contain spaces and Unicode. The server checks storage and name validity.'}</p>
      <div className="folder-actions">
        <button type="submit" disabled={!!invalid || !workflow.canStart()}>Create Session</button>
        <button type="button" disabled={view.busy || !!view.root?.parentPath} onClick={() => setParent(null)}>Back</button>
        <button type="button" disabled={view.busy} onClick={close}>Cancel</button>
      </div>
    </form>)}
    {(attempt || view.busy || view.blocked) && <>
      <p className="startup-note">After submission, work may continue on the server if you leave this screen or lose the connection. Returning to startup does not cancel or undo creation. Inspect uncertain results before creating again.</p>
      <button type="button" disabled={view.busy} onClick={close}>Return to startup / open manually</button>
    </>}
  </section>
}
