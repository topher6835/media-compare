import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import {
  checkExactDuplicateCleanupSafety,
  exactDuplicateFilterSearch,
  parseExactDuplicateFilters,
  type CleanupPreflightResponse,
} from '../api/exactDuplicates.ts'
import { duplicateReference, pluralize } from './duplicateFormatting.ts'
import {
  aggregateCleanupPlan,
  clearCleanupPlan,
  formatCleanupSavings,
  getCleanupPlan,
  removeCleanupPlanEntry,
  type DuplicateCleanupCopySnapshot,
  type DuplicateCleanupPlanEntry,
} from './duplicateCleanupPlan.ts'
import {
  aggregateCleanupSafety,
  cleanupSafetyLabels,
  cleanupSafetyReasonMessage,
  findCleanupFileResult,
  invalidateCleanupSafetyResults,
  type CleanupSafetyState,
} from './duplicateCleanupSafety.ts'

function PlannedCopy({ copy, result }: {
  copy: DuplicateCleanupCopySnapshot
  result?: CleanupPreflightResponse
}) {
  const fileResult = findCleanupFileResult(result, copy.fileEntryId)
  return (
    <div className="planned-copy">
      <p><strong>{copy.label}</strong> · FileEntry #{copy.fileEntryId}</p>
      {fileResult && (
        <p className="file-safety-result" role="status" aria-live="polite">
          <span className={`status-badge safety-${fileResult.status.toLowerCase()}`}>
            {cleanupSafetyLabels[fileResult.status]}
          </span>{' '}
          {fileResult.status === 'BLOCKED' && cleanupSafetyReasonMessage(fileResult.reason)}
        </p>
      )}
      <ul className="physical-copy-paths" aria-label={`Source paths for FileEntry ${copy.fileEntryId}`}>
        {copy.sourcePaths.map((path, index) => (
          <li className="physical-copy-path" key={`${path.sourceId}-${index}`}>
            <div className="source-path-heading">
              <strong>{path.sourceName} <span className="planner-note">#{path.sourceId}</span></strong>
              <span className={`status-badge ${path.presenceStatus === 'PRESENT' ? 'present' : 'missing'}`}>{path.presenceStatus}</span>
            </div>
            <p className="file-path">{path.relativePath}</p>
          </li>
        ))}
      </ul>
    </div>
  )
}

export function DuplicateCleanupPlanPage() {
  const [searchParameters] = useSearchParams()
  const filterSearch = exactDuplicateFilterSearch(parseExactDuplicateFilters(searchParameters))
  const [entries, setEntries] = useState(getCleanupPlan)
  const [confirmClear, setConfirmClear] = useState(false)
  const [message, setMessage] = useState('')
  const [safetyStates, setSafetyStates] = useState<Record<string, CleanupSafetyState>>({})
  const [checking, setChecking] = useState(false)
  const [progress, setProgress] = useState<{ current: number; total: number } | null>(null)
  const activeCheck = useRef<AbortController | null>(null)
  const summary = aggregateCleanupPlan(entries)
  const safetySummary = aggregateCleanupSafety(entries.map((entry) => entry.digestHex), safetyStates)

  useEffect(() => {
    window.scrollTo(0, 0)
    return () => { activeCheck.current?.abort() }
  }, [])

  async function checkGroup(entry: DuplicateCleanupPlanEntry, signal: AbortSignal) {
    setSafetyStates((states) => ({ ...states, [entry.digestHex]: { status: 'CHECKING' } }))
    try {
      const result = await checkExactDuplicateCleanupSafety(entry.digestHex, {
        keeperFileEntryId: entry.keeper.fileEntryId,
        candidateFileEntryIds: entry.candidates.map((copy) => copy.fileEntryId),
      }, signal)
      if (!signal.aborted) {
        setSafetyStates((states) => ({ ...states, [entry.digestHex]: { status: result.status, result } }))
      }
    } catch {
      if (!signal.aborted) {
        setSafetyStates((states) => ({ ...states, [entry.digestHex]: { status: 'CHECK_FAILED' } }))
      }
    }
  }

  async function checkPlan(groups: DuplicateCleanupPlanEntry[], entirePlan: boolean) {
    // The ref also prevents a second request before React renders disabled controls.
    if (activeCheck.current) return
    const controller = new AbortController()
    activeCheck.current = controller
    setChecking(true)
    setMessage('')
    if (entirePlan) {
      setSafetyStates((states) => invalidateCleanupSafetyResults(groups.map((entry) => entry.digestHex), states))
    }
    try {
      for (let index = 0; index < groups.length; index++) {
        if (controller.signal.aborted) break
        if (entirePlan) setProgress({ current: index + 1, total: groups.length })
        // Await each group, including failures, before starting more disk reads.
        await checkGroup(groups[index], controller.signal)
      }
    } finally {
      if (!controller.signal.aborted) {
        activeCheck.current = null
        setChecking(false)
        setProgress(null)
        if (entirePlan) setMessage(`Safety checks finished for ${pluralize(groups.length, 'group')}`)
      }
    }
  }

  function removeGroup(digestHex: string) {
    if (activeCheck.current) return
    removeCleanupPlanEntry(digestHex)
    setEntries(getCleanupPlan())
    setSafetyStates((states) => {
      const remaining = { ...states }
      delete remaining[digestHex]
      return remaining
    })
    setConfirmClear(false)
    setMessage(`${duplicateReference(digestHex)} removed from the cleanup plan`)
  }

  function clearPlan() {
    if (activeCheck.current) return
    clearCleanupPlan()
    setEntries(getCleanupPlan())
    setSafetyStates({})
    setConfirmClear(false)
    setMessage('Cleanup plan cleared')
  }

  return (
    <section className="page-section cleanup-plan-page">
      <Link className="back-button" to={`/duplicates${filterSearch}`}>← Back to exact duplicates</Link>
      <div className="page-heading">
        <div>
          <p className="eyebrow">Browser-session review snapshot</p>
          <h1>Cleanup plan</h1>
          <p className="page-intro">Run read-only safety checks against the current files and catalog.</p>
          <p className="planner-note">Reloading clears this plan. No decision is persisted and no files are changed. Source paths describe memberships, not additional physical copies. Savings are logical estimates, not guaranteed recoverable filesystem space.</p>
          <p className="planner-note">Saved plan snapshots are not authority. Safety results are informational, immediately stale, and cleared when you leave this page. Any future file operation must repeat critical validation immediately before changing files.</p>
        </div>
      </div>
      <p className="plan-message" role="status">{progress ? `Checking ${progress.current} of ${progress.total} groups` : message}</p>

      {entries.length === 0 ? (
        <div className="state-panel empty-panel">
          <h2>No groups in the cleanup plan</h2>
          <Link className="primary-link" to={`/duplicates${filterSearch}`}>Browse Exact Duplicates</Link>
        </div>
      ) : (
        <>
          <dl className="cleanup-stats cleanup-plan-summary">
            <div><dt>Planned duplicate groups</dt><dd>{summary.groupCount.toLocaleString()}</dd></div>
            <div><dt>Copies to keep</dt><dd>{summary.keeperCount.toLocaleString()}</dd></div>
            <div><dt>Removal candidates</dt><dd>{summary.candidateCount.toLocaleString()}</dd></div>
            <div className="highlight-stat"><dt>Estimated logical savings</dt><dd>{formatCleanupSavings(summary.estimatedSavingsBytes)}</dd></div>
          </dl>

          <section className="cleanup-safety-summary" aria-labelledby="safety-summary-heading">
            <div className="plan-actions">
              <h2 id="safety-summary-heading">Safety checks</h2>
              <button type="button" disabled={checking} onClick={() => void checkPlan(entries, true)}>Check cleanup plan</button>
            </div>
            <dl className="cleanup-stats" role="status" aria-live="polite">
              <div><dt>Ready now</dt><dd>{safetySummary.readyNow}</dd></div>
              <div><dt>Blocked</dt><dd>{safetySummary.blocked}</dd></div>
              <div><dt>Not checked</dt><dd>{safetySummary.notChecked}</dd></div>
              <div><dt>Check failed</dt><dd>{safetySummary.failed}</dd></div>
              <div><dt>Checking</dt><dd>{safetySummary.checking}</dd></div>
            </dl>
          </section>

          <div className="duplicate-list cleanup-plan-groups">
            {entries.map((entry) => {
              const safety = safetyStates[entry.digestHex] ?? { status: 'NOT_CHECKED' }
              const result = 'result' in safety ? safety.result : undefined
              return (
                <article className="duplicate-card" key={entry.digestHex}>
                  <div className="duplicate-card-heading">
                    <div>
                      <h2>{duplicateReference(entry.digestHex)}</h2>
                      <p className="planner-note">{pluralize(entry.candidateCount, 'removal candidate')}</p>
                    </div>
                    <p className="savings"><span>Estimated logical savings</span><strong>{formatCleanupSavings(entry.estimatedSavingsBytes)}</strong></p>
                  </div>
                  <div className="cleanup-safety-result" role="status" aria-live="polite" aria-label={`Safety check for ${duplicateReference(entry.digestHex)}`}>
                    <span className={`status-badge safety-${safety.status.toLowerCase()}`}>{cleanupSafetyLabels[safety.status]}</span>
                    {safety.status === 'CHECKING' && <p>Checking current catalog authority and reading file bytes…</p>}
                    {safety.status === 'NOT_CHECKED' && <p>This group has not been checked.</p>}
                    {safety.status === 'CHECK_FAILED' && <p>The safety request could not be completed. Safety is unavailable; use Check again to retry.</p>}
                    {result?.status === 'READY' && (
                      <>
                        <p>The backend safety check passed at that moment.</p>
                        <p>Current removal candidates: {result.candidateCount.toLocaleString()} · Backend-confirmed estimated logical savings: {formatCleanupSavings(result.estimatedSavingsBytes)}</p>
                        <p className="planner-note">Informational only; this result is immediately stale. Critical checks must be repeated immediately before any future file operation.</p>
                      </>
                    )}
                    {result?.status === 'BLOCKED' && (
                      <>
                        <p>{cleanupSafetyReasonMessage(result.reason)}</p>
                        <p>Review the group and explicitly update the plan. This check has not changed your saved decision.</p>
                      </>
                    )}
                  </div>
                  <div className="cleanup-plan-copies">
                    <h3>Copy to keep</h3>
                    <PlannedCopy copy={entry.keeper} result={result} />
                    <h3>Removal candidates</h3>
                    {entry.candidates.map((copy) => <PlannedCopy key={copy.fileEntryId} copy={copy} result={result} />)}
                    <details className="planned-digest"><summary>SHA-256 digest</summary><code>{entry.digestHex}</code></details>
                  </div>
                  <div className="card-footer">
                    <Link className="detail-link" to={`/duplicates/${entry.digestHex}${filterSearch}`}>Review group</Link>
                    <button type="button" disabled={checking} onClick={() => void checkPlan([entry], false)} aria-label={`${safety.status === 'NOT_CHECKED' ? 'Check safety for' : 'Check again for'} ${duplicateReference(entry.digestHex)}`}>
                      {safety.status === 'NOT_CHECKED' ? 'Check safety' : safety.status === 'CHECKING' ? 'Checking…' : 'Check again'}
                    </button>
                    <button type="button" className="secondary-link" disabled={checking} onClick={() => removeGroup(entry.digestHex)} aria-label={`Remove ${duplicateReference(entry.digestHex)} from plan`}>Remove from plan</button>
                  </div>
                </article>
              )
            })}
          </div>

          <div className="cleanup-plan-banner">
            <button
              type="button"
              className="secondary-link"
              disabled={checking}
              aria-expanded={confirmClear}
              aria-controls="clear-plan-confirmation"
              onClick={() => setConfirmClear(!confirmClear)}
            >Clear cleanup plan</button>
            <div id="clear-plan-confirmation" hidden={!confirmClear}>
              <div role="group" aria-labelledby="clear-plan-question">
                <p id="clear-plan-question">Clear all groups from this browser-session plan?</p>
                <div className="plan-actions">
                  <button type="button" disabled={checking} onClick={clearPlan}>Confirm clear plan</button>
                  <button type="button" className="secondary-link" onClick={() => setConfirmClear(false)}>Cancel</button>
                </div>
              </div>
            </div>
          </div>
        </>
      )}
    </section>
  )
}
