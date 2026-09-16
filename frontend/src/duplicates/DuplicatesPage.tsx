import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'

import {
  ApiError,
  getExactDuplicateGroups,
  type ExactDuplicateGroupSummary,
} from '../api/exactDuplicates.ts'
import {
  duplicateReference,
  formatBytes,
  pluralize,
} from './duplicateFormatting.ts'
import {
  getDuplicateListMemory,
  rememberDuplicateListScroll,
  rememberDuplicatePage,
} from './duplicateSession.ts'

function listErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 400) {
    return 'The duplicate list request was not valid. Return to this page and try again.'
  }
  return 'Exact duplicates could not be loaded. Check that the backend is running and try again.'
}

interface DuplicateGroupListProps {
  groups: ExactDuplicateGroupSummary[]
}

export function DuplicateGroupList({ groups }: DuplicateGroupListProps) {
  return (
    <div className="duplicate-list">
      {groups.map((group) => (
        <article className="duplicate-card" key={group.digestHex}>
          <div className="duplicate-card-heading">
            <div>
              <p className="duplicate-reference">
                {duplicateReference(group.digestHex)}
              </p>
              <p className="size-value">{formatBytes(group.sizeBytes)} each</p>
            </div>
            <p className="savings">
              <span>Estimated potential savings</span>
              <strong>{formatBytes(group.potentialStorageSavingsBytes)}</strong>
            </p>
          </div>

          <dl
            className={`summary-stats${group.missingOccurrenceCount > 0 ? ' has-missing' : ''}`}
          >
            <div>
              <dt>Exact members</dt>
              <dd>{group.contentRecordCount.toLocaleString()}</dd>
            </div>
            <div>
              <dt>Sources</dt>
              <dd>{group.sourceCount.toLocaleString()}</dd>
            </div>
            <div>
              <dt>Present</dt>
              <dd>{group.presentOccurrenceCount.toLocaleString()}</dd>
            </div>
            {group.missingOccurrenceCount > 0 && (
              <div className="missing-stat">
                <dt>Missing</dt>
                <dd>{group.missingOccurrenceCount.toLocaleString()}</dd>
              </div>
            )}
          </dl>

          <div className="card-footer">
            <span>
              {pluralize(group.contentRecordCount, 'ContentRecord')} share this
              digest
            </span>
            <Link
              className="detail-link"
              to={`/duplicates/${group.digestHex}`}
              state={{ fromDuplicates: true }}
              onClick={() => rememberDuplicateListScroll(window.scrollY)}
            >
              Review group <span aria-hidden="true">→</span>
            </Link>
          </div>
        </article>
      ))}
    </div>
  )
}

export function DuplicatesPage() {
  const memory = getDuplicateListMemory()
  const [groups, setGroups] = useState(memory.groups)
  const [nextCursor, setNextCursor] = useState(memory.nextAfterDigestHex)
  const [isInitialLoading, setIsInitialLoading] = useState(!memory.hasLoaded)
  const [isLoadingMore, setIsLoadingMore] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    if (memory.hasLoaded) {
      window.requestAnimationFrame(() => window.scrollTo(0, memory.scrollY))
      return () => rememberDuplicateListScroll(window.scrollY)
    }

    getExactDuplicateGroups()
      .then((page) => {
        if (cancelled) return
        setGroups(rememberDuplicatePage(page, false))
        setNextCursor(page.nextAfterDigestHex)
      })
      .catch((error: unknown) => {
        if (!cancelled) setErrorMessage(listErrorMessage(error))
      })
      .finally(() => {
        if (!cancelled) setIsInitialLoading(false)
      })

    return () => {
      cancelled = true
      rememberDuplicateListScroll(window.scrollY)
    }
  }, [memory.hasLoaded, memory.scrollY])

  async function loadMore() {
    if (!nextCursor || isLoadingMore) return

    setIsLoadingMore(true)
    setErrorMessage(null)
    try {
      const page = await getExactDuplicateGroups(nextCursor)
      setGroups(rememberDuplicatePage(page, true))
      setNextCursor(page.nextAfterDigestHex)
    } catch (error) {
      setErrorMessage(listErrorMessage(error))
    } finally {
      setIsLoadingMore(false)
    }
  }

  return (
    <section className="page-section">
      <div className="page-heading">
        <div>
          <p className="eyebrow">Read-only catalog view</p>
          <h1>Exact Duplicates</h1>
          <p className="page-intro">
            Byte-for-byte matches grouped by SHA-256 digest. Savings are logical
            estimates, not guaranteed recoverable filesystem space.
          </p>
        </div>
        {groups.length > 0 && (
          <p className="result-count">{pluralize(groups.length, 'group')} loaded</p>
        )}
      </div>

      {isInitialLoading && (
        <div className="state-panel" role="status">
          <span className="spinner" aria-hidden="true" />
          Loading exact duplicate groups…
        </div>
      )}

      {!isInitialLoading && errorMessage && groups.length === 0 && (
        <div className="state-panel error-panel" role="alert">
          <h2>Unable to load duplicates</h2>
          <p>{errorMessage}</p>
          <button type="button" onClick={() => window.location.reload()}>
            Try again
          </button>
        </div>
      )}

      {!isInitialLoading && !errorMessage && groups.length === 0 && (
        <div className="state-panel empty-panel">
          <h2>No exact duplicate groups</h2>
          <p>
            The catalog does not currently contain two or more ContentRecords
            with the same trusted SHA-256 digest.
          </p>
        </div>
      )}

      {groups.length > 0 && <DuplicateGroupList groups={groups} />}

      {errorMessage && groups.length > 0 && (
        <p className="inline-error" role="alert">
          {errorMessage}
        </p>
      )}

      {nextCursor && (
        <div className="load-more-row">
          <button type="button" onClick={loadMore} disabled={isLoadingMore}>
            {isLoadingMore ? 'Loading more…' : 'Load more'}
          </button>
        </div>
      )}
    </section>
  )
}
