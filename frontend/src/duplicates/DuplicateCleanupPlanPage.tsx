import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { exactDuplicateFilterSearch, parseExactDuplicateFilters } from '../api/exactDuplicates.ts'
import { duplicateReference, pluralize } from './duplicateFormatting.ts'
import {
  aggregateCleanupPlan,
  clearCleanupPlan,
  formatCleanupSavings,
  getCleanupPlan,
  removeCleanupPlanEntry,
  type DuplicateCleanupCopySnapshot,
} from './duplicateCleanupPlan.ts'

function PlannedCopy({ copy }: { copy: DuplicateCleanupCopySnapshot }) {
  return (
    <div className="planned-copy">
      <p><strong>{copy.label}</strong> · FileEntry #{copy.fileEntryId}</p>
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
  const summary = aggregateCleanupPlan(entries)

  useEffect(() => { window.scrollTo(0, 0) }, [])

  function removeGroup(digestHex: string) {
    removeCleanupPlanEntry(digestHex)
    setEntries(getCleanupPlan())
    setConfirmClear(false)
    setMessage(`${duplicateReference(digestHex)} removed from the cleanup plan`)
  }

  function clearPlan() {
    clearCleanupPlan()
    setEntries(getCleanupPlan())
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
          <p className="page-intro">Planning only — verify the group again before any future file operation.</p>
          <p className="planner-note">Reloading clears this plan. No decision is persisted and no files are changed. Source paths describe memberships, not additional physical copies. Savings are logical estimates, not guaranteed recoverable filesystem space.</p>
        </div>
      </div>
      <p className="plan-message" role="status">{message}</p>

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

          <div className="duplicate-list cleanup-plan-groups">
            {entries.map((entry) => (
              <article className="duplicate-card" key={entry.digestHex}>
                <div className="duplicate-card-heading">
                  <div>
                    <h2>{duplicateReference(entry.digestHex)}</h2>
                    <p className="planner-note">{pluralize(entry.candidateCount, 'removal candidate')}</p>
                  </div>
                  <p className="savings"><span>Estimated logical savings</span><strong>{formatCleanupSavings(entry.estimatedSavingsBytes)}</strong></p>
                </div>
                <div className="cleanup-plan-copies">
                  <h3>Copy to keep</h3>
                  <PlannedCopy copy={entry.keeper} />
                  <h3>Removal candidates</h3>
                  {entry.candidates.map((copy) => <PlannedCopy key={copy.fileEntryId} copy={copy} />)}
                  <details className="planned-digest"><summary>SHA-256 digest</summary><code>{entry.digestHex}</code></details>
                </div>
                <div className="card-footer">
                  <Link className="detail-link" to={`/duplicates/${entry.digestHex}${filterSearch}`}>Review group</Link>
                  <button type="button" className="secondary-link" onClick={() => removeGroup(entry.digestHex)} aria-label={`Remove ${duplicateReference(entry.digestHex)} from plan`}>Remove from plan</button>
                </div>
              </article>
            ))}
          </div>

          <div className="cleanup-plan-banner">
            <button
              type="button"
              className="secondary-link"
              aria-expanded={confirmClear}
              aria-controls="clear-plan-confirmation"
              onClick={() => setConfirmClear(!confirmClear)}
            >Clear cleanup plan</button>
            <div id="clear-plan-confirmation" hidden={!confirmClear}>
              <div role="group" aria-labelledby="clear-plan-question">
                <p id="clear-plan-question">Clear all groups from this browser-session plan?</p>
                <div className="plan-actions">
                  <button type="button" onClick={clearPlan}>Confirm clear plan</button>
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
