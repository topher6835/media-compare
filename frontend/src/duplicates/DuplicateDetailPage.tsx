import { useEffect, useState } from 'react'
import {
  Link,
  useLocation,
  useNavigate,
  useParams,
} from 'react-router-dom'

import {
  ApiError,
  getExactDuplicateGroup,
  type ExactDuplicateGroupDetail,
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
      message: 'This URL does not contain a valid full SHA-256 digest.',
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

interface DuplicateGroupDetailProps {
  detail: ExactDuplicateGroupDetail
}

export function DuplicateGroupDetail({ detail }: DuplicateGroupDetailProps) {
  const extensions = occurrenceExtensions(detail.occurrences)

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
          <dt>Present occurrences</dt>
          <dd>{detail.presentOccurrenceCount.toLocaleString()}</dd>
        </div>
        <div>
          <dt>Missing occurrences</dt>
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
        Potential savings is a logical estimate based on present occurrences;
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
            <h2 id="occurrences-heading">Retained occurrences</h2>
            <p>Present and missing retained FileEntry occurrences, in catalog order.</p>
          </div>
          <span>{pluralize(detail.occurrences.length, 'occurrence')}</span>
        </div>
        {detail.occurrences.length === 0 ? (
          <p className="subtle-empty">No retained FileEntry occurrences.</p>
        ) : (
          <div className="occurrence-list">
            {detail.occurrences.map((occurrence) => {
              const isMissing = occurrence.presenceStatus === 'MISSING'
              return (
                <article
                  className={`occurrence-row${isMissing ? ' is-missing' : ''}`}
                  key={occurrence.fileEntryId}
                >
                  <div className="occurrence-main">
                    <div className="filename-row">
                      <h3>{filenameFromPath(occurrence.relativePath)}</h3>
                      <span
                        className={`status-badge ${isMissing ? 'missing' : 'present'}`}
                      >
                        {occurrence.presenceStatus}
                      </span>
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
  const [result, setResult] = useState<{
    digestHex: string
    detail: ExactDuplicateGroupDetail | null
    failure: DetailFailure | null
  }>({ digestHex: '', detail: null, failure: null })

  useEffect(() => {
    let cancelled = false
    window.scrollTo(0, 0)

    getExactDuplicateGroup(digestHex)
      .then((result) => {
        if (cancelled) return
        recordDuplicateVisit({
          digestHex: result.digestHex,
          reference: duplicateReference(result.digestHex),
        })
        setResult({ digestHex, detail: result, failure: null })
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setResult({
            digestHex,
            detail: null,
            failure: detailFailure(error),
          })
        }
      })

    return () => {
      cancelled = true
    }
  }, [digestHex])

  const detail = result.digestHex === digestHex ? result.detail : null
  const failure = result.digestHex === digestHex ? result.failure : null
  const isLoading = !detail && !failure
  const trail = detail ? getDuplicateTrail() : []
  const cameFromList = Boolean(
    (location.state as { fromDuplicates?: boolean } | null)?.fromDuplicates,
  )

  function goBack() {
    if (cameFromList) {
      navigate(-1)
    } else {
      navigate('/duplicates')
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
            <Link className="primary-link" to="/duplicates">
              Browse exact duplicates
            </Link>
          )}
        </div>
      )}

      {!isLoading && detail && <DuplicateGroupDetail detail={detail} />}

      {trail.length > 1 && (
        <nav className="visit-trail" aria-label="Recent duplicate group visits">
          <span>Recent groups</span>
          <ol>
            {trail.map((entry, index) => (
              <li key={`${entry.digestHex}-${index}`}>
                {entry.digestHex === digestHex ? (
                  <span aria-current="page">{entry.reference}</span>
                ) : (
                  <Link to={`/duplicates/${entry.digestHex}`}>{entry.reference}</Link>
                )}
              </li>
            ))}
          </ol>
        </nav>
      )}
    </section>
  )
}
