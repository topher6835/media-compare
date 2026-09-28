import { useState } from 'react'
import type { ExactDuplicateGroupDetail } from '../api/exactDuplicates.ts'
import { filenameFromPath, formatBytes, pluralize } from './duplicateFormatting.ts'
import { groupPhysicalCopies } from './duplicatePhysicalCopies.ts'

interface DuplicatePhysicalCopiesProps {
  detail: ExactDuplicateGroupDetail
  filtersActive: boolean
}

export function DuplicatePhysicalCopies({ detail, filtersActive }: DuplicatePhysicalCopiesProps) {
  const [keeperId, setKeeperId] = useState<number | null>(null)
  const copies = groupPhysicalCopies(detail.occurrences)
  const presentCopies = copies.filter((copy) => copy.presenceStatus === 'PRESENT')
  const keeper = presentCopies.find((copy) => copy.fileEntryId === keeperId)
  const canPreview = presentCopies.length >= 2
  const candidateCount = keeper ? presentCopies.length - 1 : 0

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
        <h3>Cleanup preview</h3>
        <p>Planning only — Media Compare will not change or delete any files.</p>
        <p className="planner-note">
          Selection is temporary and is not saved. Presence reflects the catalog.
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
                <div><dt>Potential logical savings (estimate)</dt><dd>{formatBytes(candidateCount * detail.sizeBytes)}</dd></div>
              </dl>
              <p className="planner-note">
                Source paths are informational relationships, not separate removal actions.
                Savings are not guaranteed recoverable filesystem space.
              </p>
            </>
          )}
        </div>
        {keeper && (
          <button type="button" onClick={() => setKeeperId(null)}>Clear selection</button>
        )}
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
                        onChange={() => setKeeperId(copy.fileEntryId)}
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
