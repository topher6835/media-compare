import { useEffect, useMemo, useState } from 'react'
import {
  Link,
  useLocation,
  useNavigate,
  useParams,
  useSearchParams,
} from 'react-router-dom'

import {
  ApiError,
  exactDuplicateFilterKey,
  exactDuplicateFilterParameters,
  exactDuplicateFilterSearch,
  getExactDuplicateGroup,
  hasExactDuplicateFilters,
  parseExactDuplicateFilters,
  type ExactDuplicateFilters,
  type ExactDuplicateGroupDetail,
  type TechnicalFileCategory,
} from '../api/exactDuplicates.ts'
import {
  duplicateReference,
  filenameFromPath,
  formatBytes,
  occurrenceExtensions,
  pluralize,
} from './duplicateFormatting.ts'
import {
  getDuplicateTrail,
  recordDuplicateVisit,
} from './duplicateSession.ts'

type DetailFailure = 'bad-request' | 'not-found' | 'server'

function detailFailure(error: unknown): DetailFailure {
  if (error instanceof ApiError) {
    if (error.status === 400) return 'bad-request'
    if (error.status === 404) return 'not-found'
  }
  return 'server'
}

function failureContent(failure: DetailFailure) {
  if (failure === 'bad-request') {
    return {
      heading: 'Invalid duplicate reference',
      message: 'This URL does not contain a valid duplicate request.',
    }
  }
  if (failure === 'not-found') {
    return {
      heading: 'Duplicate group not found',
      message:
        'This exact duplicate group does not exist, or it no longer contains multiple ContentRecords.',
    }
  }
  return {
    heading: 'Unable to load this group',
    message: 'The backend could not complete the request. Please try again.',
  }
}

function categoryLabel(category: TechnicalFileCategory): string {
  if (category === 'PHOTO') return 'Photos'
  if (category === 'VIDEO') return 'Videos'
  return 'Documents'
}

function filterDescription(filters: ExactDuplicateFilters): string {
  const parts: string[] = []
  if (filters.fileCategories.length > 0) {
    parts.push(filters.fileCategories.map(categoryLabel).join(' + '))
  }
  if (filters.extensions.length > 0) {
    parts.push(filters.extensions.join(', '))
  }
  return parts.join(' · ')
}

interface DuplicateGroupDetailProps {
  detail: ExactDuplicateGroupDetail
  filters: ExactDuplicateFilters
}

export function DuplicateGroupDetail({
  detail,
  filters,
}: DuplicateGroupDetailProps) {
  const extensions = occurrenceExtensions(detail.occurrences)
  const filtersActive = hasExactDuplicateFilters(filters)
  const matchingCount = detail.occurrences.filter(
    (occurrence) => occurrence.matchesFilter,
  ).length
  const additionalCount = detail.occurrences.length - matchingCount

  return (
    <>
      <div className="detail-title-row">
        <div>
          <p className="eyebrow">Exact duplicate group</p>
          <h1>{duplicateReference(detail.digestHex)}</h1>
        </div>
        {extensions.length > 0 && (
          <div className="extension-summary">
            {extensions.length > 1 && <strong>Mixed extensions</strong>}
            <span>{extensions.join(' · ')}</span>
          </div>
        )}
      </div>

      {filtersActive && (
        <div className="detail-filter-context">
          <strong>Filtered by {filterDescription(filters)}</strong>
          <span>
            {pluralize(matchingCount, 'source path')}{' '}
            {matchingCount === 1 ? 'matches' : 'match'} this filter
            {additionalCount > 0 &&
              `; ${pluralize(additionalCount, 'additional source path')} ${additionalCount === 1 ? 'remains' : 'remain'} in the exact group`}
            .
          </span>
        </div>
      )}

      <dl className="detail-summary">
        <div>
          <dt>Exact members</dt>
          <dd>{detail.contentRecordCount.toLocaleString()}</dd>
        </div>
        <div>
          <dt>Size each</dt>
          <dd>{formatBytes(detail.sizeBytes)}</dd>
        </div>
        <div>
          <dt>Present physical occurrences</dt>
          <dd>{detail.presentOccurrenceCount.toLocaleString()}</dd>
        </div>
        <div>
          <dt>Missing physical occurrences</dt>
          <dd>{detail.missingOccurrenceCount.toLocaleString()}</dd>
        </div>
        <div>
          <dt>Sources</dt>
          <dd>{detail.sourceCount.toLocaleString()}</dd>
        </div>
        <div className="highlight-stat">
          <dt>Estimated potential savings</dt>
          <dd>{formatBytes(detail.potentialStorageSavingsBytes)}</dd>
        </div>
      </dl>
      <p className="estimate-note">
        Potential savings is a logical estimate based on present physical FileEntries;
        it is not guaranteed recoverable filesystem space.
      </p>

      <section className="content-section" aria-labelledby="members-heading">
        <div className="section-heading-row">
          <div>
            <h2 id="members-heading">ContentRecord members</h2>
            <p>
              Distinct catalog records sharing this exact digest. No member is
              selected as a keeper.
            </p>
          </div>
          <span>{pluralize(detail.members.length, 'member')}</span>
        </div>
        <div className="member-list">
          {detail.members.map((member) => (
            <div className="member-row" key={member.contentRecordId}>
              <span>
                ContentRecord <strong>#{member.contentRecordId}</strong>
              </span>
              <span>{formatBytes(member.sizeBytes)}</span>
            </div>
          ))}
        </div>
      </section>

      <section className="content-section" aria-labelledby="occurrences-heading">
        <div className="section-heading-row">
          <div>
            <h2 id="occurrences-heading">Retained Source paths</h2>
            <p>
              Source relationships for retained FileEntries, in catalog order.
              One physical FileEntry can appear under multiple Sources. Filters
              highlight matches without removing exact-group context.
            </p>
          </div>
          <span>{pluralize(detail.occurrences.length, 'source path')}</span>
        </div>
        {detail.occurrences.length === 0 ? (
          <p className="subtle-empty">No retained Source paths.</p>
        ) : (
          <div className="occurrence-list">
            {detail.occurrences.map((occurrence) => {
              const isMissing = occurrence.presenceStatus === 'MISSING'
                || occurrence.applicabilityStatus === 'RETIRED'
              const matchClass = filtersActive
                ? occurrence.matchesFilter
                  ? ' is-filter-match'
                  : ' is-filter-nonmatch'
                : ''
              return (
                <article
                  className={`occurrence-row${isMissing ? ' is-missing' : ''}${matchClass}`}
                  key={occurrence.membershipId}
                >
                  <div className="occurrence-main">
                    <div className="filename-row">
                      <h3>{filenameFromPath(occurrence.relativePath)}</h3>
                      <span
                        className={`status-badge ${isMissing ? 'missing' : 'present'}`}
                      >
                        {occurrence.applicabilityStatus === 'RETIRED'
                          ? 'RETIRED'
                          : occurrence.presenceStatus}
                      </span>
                      {filtersActive && occurrence.matchesFilter && (
                        <span className="status-badge filter-match">FILTER MATCH</span>
                      )}
                    </div>
                    <p className="file-path" title={occurrence.relativePath}>
                      {occurrence.relativePath}
                    </p>
                  </div>
                  <dl className="occurrence-meta">
                    <div>
                      <dt>Source</dt>
                      <dd>
                        {occurrence.sourceName}{' '}
                        <span>#{occurrence.sourceId}</span>
                      </dd>
                    </div>
                    <div>
                      <dt>ContentRecord</dt>
                      <dd>#{occurrence.contentRecordId}</dd>
                    </div>
                    <div>
                      <dt>Extension</dt>
                      <dd>{occurrence.extension ?? 'None'}</dd>
                    </div>
                    <div>
                      <dt>File type</dt>
                      <dd>
                        {occurrence.fileCategory
                          ? categoryLabel(occurrence.fileCategory).replace(/s$/, '')
                          : 'Unclassified'}
                      </dd>
                    </div>
                  </dl>
                </article>
              )
            })}
          </div>
        )}
      </section>

      <details className="technical-details">
        <summary>Technical identity</summary>
        <dl>
          <div>
            <dt>SHA-256 digest</dt>
            <dd>
              <code>{detail.digestHex}</code>
            </dd>
          </div>
        </dl>
      </details>
    </>
  )
}

export function DuplicateDetailPage() {
  const { digestHex = '' } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParameters, setSearchParameters] = useSearchParams()
  const rawSearch = searchParameters.toString()
  const parsedFilters = useMemo(
    () => parseExactDuplicateFilters(new URLSearchParams(rawSearch)),
    [rawSearch],
  )
  const filterKey = exactDuplicateFilterKey(parsedFilters)
  const filters = useMemo(
    () => parseExactDuplicateFilters(new URLSearchParams(filterKey)),
    [filterKey],
  )
  const filterSearch = exactDuplicateFilterSearch(filters)
  const requestKey = `${digestHex}?${filterKey}`
  const [result, setResult] = useState<{
    requestKey: string
    detail: ExactDuplicateGroupDetail | null
    failure: DetailFailure | null
  }>({ requestKey: '', detail: null, failure: null })

  useEffect(() => {
    if (rawSearch !== filterKey) {
      setSearchParameters(exactDuplicateFilterParameters(filters), {
        replace: true,
      })
    }
  }, [filterKey, filters, rawSearch, setSearchParameters])

  useEffect(() => {
    let cancelled = false
    window.scrollTo(0, 0)

    getExactDuplicateGroup(digestHex, filters)
      .then((detail) => {
        if (cancelled) return
        recordDuplicateVisit({
          digestHex: detail.digestHex,
          reference: duplicateReference(detail.digestHex),
        })
        setResult({ requestKey, detail, failure: null })
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setResult({
            requestKey,
            detail: null,
            failure: detailFailure(error),
          })
        }
      })

    return () => {
      cancelled = true
    }
  }, [digestHex, filters, requestKey])

  const detail = result.requestKey === requestKey ? result.detail : null
  const failure = result.requestKey === requestKey ? result.failure : null
  const isLoading = !detail && !failure
  const trail = detail ? getDuplicateTrail() : []
  const cameFromList = Boolean(
    (location.state as { fromDuplicates?: boolean } | null)?.fromDuplicates,
  )

  function goBack() {
    if (cameFromList) {
      navigate(-1)
    } else {
      navigate(`/duplicates${filterSearch}`)
    }
  }

  return (
    <section className="page-section detail-page">
      <button className="back-button" type="button" onClick={goBack}>
        <span aria-hidden="true">←</span> Back to exact duplicates
      </button>

      {isLoading && (
        <div className="state-panel" role="status">
          <span className="spinner" aria-hidden="true" /> Loading duplicate group…
        </div>
      )}

      {!isLoading && failure && (
        <div className="state-panel error-panel" role="alert">
          <h1>{failureContent(failure).heading}</h1>
          <p>{failureContent(failure).message}</p>
          {failure === 'server' ? (
            <button type="button" onClick={() => window.location.reload()}>
              Try again
            </button>
          ) : (
            <Link className="primary-link" to={`/duplicates${filterSearch}`}>
              Browse exact duplicates
            </Link>
          )}
        </div>
      )}

      {!isLoading && detail && (
        <DuplicateGroupDetail detail={detail} filters={filters} />
      )}

      {trail.length > 1 && (
        <nav className="visit-trail" aria-label="Recent duplicate group visits">
          <span>Recent groups</span>
          <ol>
            {trail.map((entry, index) => (
              <li key={`${entry.digestHex}-${index}`}>
                {entry.digestHex === digestHex ? (
                  <span aria-current="page">{entry.reference}</span>
                ) : (
                  <Link to={`/duplicates/${entry.digestHex}${filterSearch}`}>
                    {entry.reference}
                  </Link>
                )}
              </li>
            ))}
          </ol>
        </nav>
      )}
    </section>
  )
}
