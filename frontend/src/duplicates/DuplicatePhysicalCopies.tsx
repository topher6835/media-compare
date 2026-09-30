import { useState } from 'react'
import { Link } from 'react-router-dom'
import type { ExactDuplicateGroupDetail } from '../api/exactDuplicates.ts'
import { filenameFromPath, pluralize } from './duplicateFormatting.ts'
import { groupPhysicalCopies } from './duplicatePhysicalCopies.ts'
import { RevealFileButton } from '../library/RevealFileButton.tsx'
import {
  createCleanupPlanEntry,
  estimateCleanupSavings,
  formatCleanupSavings,
  getCleanupPlanEntry,
  removeCleanupPlanEntry,
  saveCleanupPlanEntry,
} from './duplicateCleanupPlan.ts'

interface DuplicatePhysicalCopiesProps {
  detail: ExactDuplicateGroupDetail
  filtersActive: boolean
  filterSearch: string
}

export function DuplicatePhysicalCopies({ detail, filtersActive, filterSearch }: DuplicatePhysicalCopiesProps) {
  const [plannedEntry, setPlannedEntry] = useState(() => getCleanupPlanEntry(detail.digestHex))
  const [keeperId, setKeeperId] = useState<number | null>(plannedEntry?.keeper.fileEntryId ?? null)
  const [planMessage, setPlanMessage] = useState('')
  const copies = groupPhysicalCopies(detail.occurrences)
  const presentCopies = copies.filter((copy) => copy.presenceStatus === 'PRESENT')
  const keeper = presentCopies.find((copy) => copy.fileEntryId === keeperId)
  const canPreview = presentCopies.length >= 2
  const candidateCount = keeper ? presentCopies.length - 1 : 0

  function savePlan() {
    if (!keeper) return
    const entry = createCleanupPlanEntry(detail, keeper.fileEntryId)
    if (!entry) return
    saveCleanupPlanEntry(entry)
    setPlanMessage(plannedEntry ? 'Updated cleanup plan' : 'Added to cleanup plan')
    setPlannedEntry(getCleanupPlanEntry(detail.digestHex))
  }

  function removePlan() {
    removeCleanupPlanEntry(detail.digestHex)
    setPlannedEntry(undefined)
    setPlanMessage('Removed from cleanup plan')
  }

  function chooseKeeper(id: number | null) {
    setKeeperId(id)
    setPlanMessage('')
  }

  return (
    <section className="content-section" aria-labelledby="physical-copies-heading">
      <div className="section-heading-row">
        <div>
          <h2 id="physical-copies-heading">Physical copies</h2>
          <p>
            {pluralize(presentCopies.length, 'present copy', 'present copies')} ·{' '}
            {pluralize(copies.length - presentCopies.length, 'missing copy', 'missing copies')}.
            Overlapping Source paths are grouped under one physical copy.
          </p>
        </div>
      </div>

      <div className="cleanup-preview">
        <div className="plan-actions">
          <h3>Cleanup preview</h3>
          {plannedEntry && <span className="status-badge planned">PLANNED</span>}
        </div>
        <p>Planning only — Media Compare will not change or delete any files.</p>
        <p>Planning only — verify the group again before any future file operation.</p>
        <p className="planner-note">
          Save explicitly to collect this decision in a browser-session plan. Reloading clears the plan.
          Presence reflects the catalog.
          {filtersActive && ' Filters highlight Source paths; this preview includes the complete exact group.'}
        </p>
        <div role="status" aria-live="polite">
          {!canPreview ? (
            <p>This group currently has fewer than two present physical copies. There are no redundant present copies to preview for removal.</p>
          ) : !keeper ? (
            <p>Choose the physical copy you would keep to preview the redundant copies.</p>
          ) : (
            <>
              <p className="keeper-description">
                <strong>Keeper: {filenameFromPath(keeper.occurrences[0].relativePath)}</strong>
                {' '}· FileEntry #{keeper.fileEntryId}
              </p>
              <dl className="cleanup-stats">
                <div><dt>Present physical copies retained</dt><dd>1</dd></div>
                <div><dt>Removal candidates (physical copies)</dt><dd>{candidateCount.toLocaleString()}</dd></div>
                <div><dt>Potential logical savings (estimate)</dt><dd>{formatCleanupSavings(estimateCleanupSavings(candidateCount, detail.sizeBytes))}</dd></div>
              </dl>
              <p className="planner-note">
                Source paths are informational relationships, not separate removal actions.
                Savings are not guaranteed recoverable filesystem space.
              </p>
            </>
          )}
        </div>
        <div className="plan-actions">
          {keeper && canPreview && (
            <>
              <button type="button" onClick={savePlan}>{plannedEntry ? 'Update cleanup plan' : 'Add to cleanup plan'}</button>
              <button type="button" className="secondary-link" onClick={() => chooseKeeper(null)}>Clear selection</button>
            </>
          )}
          {plannedEntry && (
            <>
              <Link to={`/duplicates/plan${filterSearch}`}>Review cleanup plan</Link>
              <button type="button" className="secondary-link" onClick={removePlan}>Remove from cleanup plan</button>
            </>
          )}
        </div>
        <p role="status">{planMessage || (plannedEntry ? 'This group has a saved session decision. Keeper changes require Update cleanup plan.' : '')}</p>
      </div>

      <fieldset className="physical-copy-list">
        <legend className="sr-only">Choose the physical copy to keep</legend>
        {copies.length === 0 && <p className="subtle-empty">No retained Source paths.</p>}
        {copies.map((copy) => {
          const isMissing = copy.presenceStatus === 'MISSING'
          const isKeeper = keeper?.fileEntryId === copy.fileEntryId
          const isCandidate = canPreview && Boolean(keeper) && !isKeeper && !isMissing
          const stateClass = isMissing ? 'is-missing' : isKeeper ? 'is-keeper' : isCandidate ? 'is-candidate' : ''
          return (
            <article className={`physical-copy ${stateClass}`} key={copy.fileEntryId}>
              <div className="physical-copy-heading">
                <div>
                  <h3 id={`copy-${copy.fileEntryId}`}>{filenameFromPath(copy.occurrences[0].relativePath)}</h3>
                  <p className="planner-note" id={`copy-identity-${copy.fileEntryId}`}>FileEntry #{copy.fileEntryId} · ContentRecord #{copy.contentRecordId}</p>
                </div>
                <div className="physical-copy-actions">
                  <span className={`status-badge ${isMissing ? 'missing' : 'present'}`}>{copy.presenceStatus}</span>
                  {isKeeper && <span className="status-badge present">KEEP</span>}
                  {isCandidate && <span className="status-badge candidate">REMOVAL CANDIDATE</span>}
                  {!isMissing && canPreview && (
                    <label className="keeper-control">
                      <input
                        type="radio"
                        name="physical-copy-keeper"
                        checked={isKeeper}
                        onChange={() => chooseKeeper(copy.fileEntryId)}
                        aria-labelledby={`keep-${copy.fileEntryId} copy-${copy.fileEntryId}`}
                        aria-describedby={`copy-identity-${copy.fileEntryId}`}
                      />
                      <span id={`keep-${copy.fileEntryId}`}>Keep this copy</span>
                    </label>
                  )}
                </div>
              </div>
              <ul className="physical-copy-paths" aria-label="Source paths">
                {copy.occurrences.map((occurrence) => (
                  <li
                    className={`physical-copy-path${filtersActive ? occurrence.matchesFilter ? ' is-filter-match' : ' is-filter-nonmatch' : ''}`}
                    key={occurrence.membershipId}
                  >
                    <div className="source-path-heading">
                      <strong>{occurrence.sourceName} <span className="planner-note">#{occurrence.sourceId}</span></strong>
                      <span className={`status-badge ${occurrence.presenceStatus === 'PRESENT' ? 'present' : 'missing'}`}>{occurrence.presenceStatus}</span>
                      {filtersActive && occurrence.matchesFilter && <span className="status-badge filter-match">FILTER MATCH</span>}
                    </div>
                    <p className="file-path">{occurrence.relativePath}</p>
                    <div className="reveal-path-row">
                      <p className="file-path full-path">{occurrence.absolutePath
                        ?? 'Full path unavailable from current trusted catalog route'}</p>
                      {!isMissing && occurrence.presenceStatus === 'PRESENT' && occurrence.absolutePath
                        && occurrence === copy.occurrences.find((route) =>
                          route.presenceStatus === 'PRESENT' && route.absolutePath)
                        && <RevealFileButton fileEntryId={copy.fileEntryId} />}
                    </div>
                    <p className="planner-note">
                      {occurrence.extension ?? 'No extension'} ·{' '}
                      {occurrence.fileCategory === 'PHOTO' ? 'Photo' : occurrence.fileCategory === 'VIDEO' ? 'Video' : occurrence.fileCategory === 'DOCUMENT' ? 'Document' : 'Unclassified'}
                    </p>
                  </li>
                ))}
              </ul>
            </article>
          )
        })}
      </fieldset>
    </section>
  )
}
