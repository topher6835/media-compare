import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import {
  ApiError,
  exactDuplicateFilterKey,
  exactDuplicateFilterParameters,
  exactDuplicateFilterSearch,
  getExactDuplicateFilterOptions,
  getExactDuplicateGroups,
  hasExactDuplicateFilters,
  parseExactDuplicateFilters,
  type ExactDuplicateFilterOption,
  type ExactDuplicateFilters,
  type ExactDuplicateGroupSummary,
  type TechnicalFileCategory,
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

const categoryChoices: Array<{
  value: TechnicalFileCategory
  label: string
}> = [
  { value: 'PHOTO', label: 'Photos' },
  { value: 'VIDEO', label: 'Videos' },
  { value: 'DOCUMENT', label: 'Documents' },
]

function listErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 400) {
    return 'The duplicate list request was not valid. Check the active filters and try again.'
  }
  return 'Exact duplicates could not be loaded. Check that the backend is running and try again.'
}

function optionCategoryLabel(category: TechnicalFileCategory | null): string {
  if (category === 'PHOTO') return 'Photo'
  if (category === 'VIDEO') return 'Video'
  if (category === 'DOCUMENT') return 'Document'
  return 'Unclassified'
}

interface DuplicateFiltersProps {
  filters: ExactDuplicateFilters
  options: ExactDuplicateFilterOption[] | null
  optionsError: boolean
  onChange: (filters: ExactDuplicateFilters) => void
  onRetryOptions: () => void
}

function DuplicateFilters({
  filters,
  options,
  optionsError,
  onChange,
  onRetryOptions,
}: DuplicateFiltersProps) {
  const active = hasExactDuplicateFilters(filters)
  const availableExtensions = new Set(
    options?.map((option) => option.extension) ?? [],
  )
  const staleExtensions = filters.extensions.filter(
    (extension) => !availableExtensions.has(extension),
  )

  function toggleCategory(category: TechnicalFileCategory) {
    const selected = filters.fileCategories.includes(category)
    onChange({
      ...filters,
      fileCategories: selected
        ? filters.fileCategories.filter((value) => value !== category)
        : [...filters.fileCategories, category],
    })
  }

  function toggleExtension(extension: string) {
    const selected = filters.extensions.includes(extension)
    onChange({
      ...filters,
      extensions: selected
        ? filters.extensions.filter((value) => value !== extension)
        : [...filters.extensions, extension],
    })
  }

  return (
    <section className="duplicate-filters" aria-labelledby="file-filters-heading">
      <div className="filter-heading-row">
        <div>
          <h2 id="file-filters-heading">Filter by file type</h2>
          <p>Groups remain complete when a retained occurrence matches.</p>
        </div>
        {active && (
          <button
            className="clear-filter-button"
            type="button"
            onClick={() => onChange({ fileCategories: [], extensions: [] })}
          >
            Clear filters
          </button>
        )}
      </div>

      <div className="filter-controls-row">
          <div className="category-filter" role="group" aria-label="File type categories">
          <button
            type="button"
            aria-pressed={filters.fileCategories.length === 0}
            onClick={() => onChange({ ...filters, fileCategories: [] })}
          >
            All
          </button>
          {categoryChoices.map((choice) => (
            <button
              type="button"
              aria-pressed={filters.fileCategories.includes(choice.value)}
              key={choice.value}
              onClick={() => toggleCategory(choice.value)}
            >
              {choice.label}
            </button>
          ))}
        </div>

        <details className="extension-filter">
          <summary>
            Extensions
            {filters.extensions.length > 0 && (
              <span>{filters.extensions.length}</span>
            )}
          </summary>
          <div className="extension-options">
            {options === null && !optionsError && (
              <p className="filter-options-status" role="status">
                Loading extensions…
              </p>
            )}
            {optionsError && (
              <div className="filter-options-status" role="alert">
                <p>Extension options are unavailable.</p>
                <button type="button" onClick={onRetryOptions}>
                  Try again
                </button>
              </div>
            )}
            {options?.length === 0 && (
              <p className="filter-options-status">
                No extensions are represented in exact duplicate groups.
              </p>
            )}
            {options?.map((option) => (
              <label className="extension-option" key={option.extension}>
                <input
                  type="checkbox"
                  checked={filters.extensions.includes(option.extension)}
                  onChange={() => toggleExtension(option.extension)}
                />
                <span>
                  <strong>{option.extension}</strong>
                  <small>
                    {optionCategoryLabel(option.fileCategory)} ·{' '}
                    {pluralize(option.exactDuplicateGroupCount, 'group')}
                  </small>
                </span>
              </label>
            ))}
            {options !== null &&
              staleExtensions.map((extension) => (
                <label className="extension-option is-stale" key={extension}>
                  <input
                    type="checkbox"
                    checked
                    onChange={() => toggleExtension(extension)}
                  />
                  <span>
                    <strong>{extension}</strong>
                    <small>Selected · not currently available</small>
                  </span>
                </label>
              ))}
          </div>
        </details>
      </div>

      {filters.extensions.length > 0 && (
        <div className="active-extension-filters" aria-label="Selected extensions">
          {filters.extensions.map((extension) => (
            <button
              type="button"
              key={extension}
              aria-label={`Remove ${extension} extension filter`}
              onClick={() => toggleExtension(extension)}
            >
              {extension} <span aria-hidden="true">×</span>
            </button>
          ))}
        </div>
      )}
    </section>
  )
}

function matchContext(group: ExactDuplicateGroupSummary): string | null {
  if (!group.filterMatch) return null

  const matching = group.filterMatch.matchingOccurrenceCount
  const extensions = group.filterMatch.matchingExtensions.join(', ')
  const total = group.presentOccurrenceCount + group.missingOccurrenceCount
  const additional = Math.max(total - matching, 0)
  const parts = [
    `${pluralize(matching, 'matching occurrence')}${extensions ? ` · ${extensions}` : ''}`,
  ]
  if (additional > 0) {
    parts.push(pluralize(additional, 'additional retained occurrence'))
  }
  return parts.join(' · ')
}

interface DuplicateGroupListProps {
  groups: ExactDuplicateGroupSummary[]
  filterSearch: string
}

export function DuplicateGroupList({
  groups,
  filterSearch,
}: DuplicateGroupListProps) {
  return (
    <div className="duplicate-list">
      {groups.map((group) => {
        const context = matchContext(group)
        return (
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

            {context && <p className="filter-match-context">{context}</p>}

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
                to={`/duplicates/${group.digestHex}${filterSearch}`}
                state={{ fromDuplicates: true }}
                onClick={() =>
                  rememberDuplicateListScroll(
                    exactDuplicateFilterKeyFromSearch(filterSearch),
                    window.scrollY,
                  )
                }
              >
                Review group <span aria-hidden="true">→</span>
              </Link>
            </div>
          </article>
        )
      })}
    </div>
  )
}

function exactDuplicateFilterKeyFromSearch(search: string): string {
  return search.startsWith('?') ? search.slice(1) : search
}

interface DuplicateResultsProps {
  filters: ExactDuplicateFilters
  filterKey: string
  filterSearch: string
  onClearFilters: () => void
}

function DuplicateResults({
  filters,
  filterKey,
  filterSearch,
  onClearFilters,
}: DuplicateResultsProps) {
  const filtersActive = hasExactDuplicateFilters(filters)
  const initialMemory = getDuplicateListMemory(filterKey)
  const [groups, setGroups] = useState(initialMemory.groups)
  const [nextCursor, setNextCursor] = useState(
    initialMemory.nextAfterDigestHex,
  )
  const [isInitialLoading, setIsInitialLoading] = useState(
    !initialMemory.hasLoaded,
  )
  const [isLoadingMore, setIsLoadingMore] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  useEffect(() => {
    let cancelled = false

    if (initialMemory.hasLoaded) {
      window.requestAnimationFrame(() =>
        window.scrollTo(0, initialMemory.scrollY),
      )
      return () => rememberDuplicateListScroll(filterKey, window.scrollY)
    }

    window.scrollTo(0, 0)

    getExactDuplicateGroups(filters)
      .then((page) => {
        if (cancelled) return
        setGroups(rememberDuplicatePage(filterKey, page, false))
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
      rememberDuplicateListScroll(filterKey, window.scrollY)
    }
  }, [filterKey, filters, initialMemory.hasLoaded, initialMemory.scrollY])

  async function loadMore() {
    if (!nextCursor || isLoadingMore) return

    setIsLoadingMore(true)
    setErrorMessage(null)
    try {
      const page = await getExactDuplicateGroups(filters, nextCursor)
      setGroups(rememberDuplicatePage(filterKey, page, true))
      setNextCursor(page.nextAfterDigestHex)
    } catch (error) {
      setErrorMessage(listErrorMessage(error))
    } finally {
      setIsLoadingMore(false)
    }
  }

  return (
    <>
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
          {filtersActive ? (
            <>
              <h2>No exact duplicate groups match these filters</h2>
              <p>The catalog may still contain groups outside this selection.</p>
              <button
                type="button"
                onClick={onClearFilters}
              >
                Clear filters
              </button>
            </>
          ) : (
            <>
              <h2>No exact duplicate groups</h2>
              <p>
                The catalog does not currently contain two or more ContentRecords
                with the same trusted SHA-256 digest.
              </p>
            </>
          )}
        </div>
      )}

      {groups.length > 0 && (
        <>
          <p className="result-count duplicate-result-count">
            {pluralize(groups.length, 'group')} loaded
          </p>
          <DuplicateGroupList groups={groups} filterSearch={filterSearch} />
        </>
      )}

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
    </>
  )
}

export function DuplicatesPage() {
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
  const [filterOptions, setFilterOptions] = useState<
    ExactDuplicateFilterOption[] | null
  >(null)
  const [filterOptionsError, setFilterOptionsError] = useState(false)
  const [filterOptionsAttempt, setFilterOptionsAttempt] = useState(0)

  useEffect(() => {
    if (rawSearch !== filterKey) {
      setSearchParameters(exactDuplicateFilterParameters(filters), {
        replace: true,
      })
    }
  }, [filterKey, filters, rawSearch, setSearchParameters])

  useEffect(() => {
    let cancelled = false

    getExactDuplicateFilterOptions()
      .then((response) => {
        if (!cancelled) setFilterOptions(response.extensions)
      })
      .catch(() => {
        if (!cancelled) setFilterOptionsError(true)
      })

    return () => {
      cancelled = true
    }
  }, [filterOptionsAttempt])

  function changeFilters(nextFilters: ExactDuplicateFilters) {
    setSearchParameters(exactDuplicateFilterParameters(nextFilters))
  }

  function retryFilterOptions() {
    setFilterOptions(null)
    setFilterOptionsError(false)
    setFilterOptionsAttempt((attempt) => attempt + 1)
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
      </div>

      <DuplicateFilters
        filters={filters}
        options={filterOptions}
        optionsError={filterOptionsError}
        onChange={changeFilters}
        onRetryOptions={retryFilterOptions}
      />

      <DuplicateResults
        key={filterKey}
        filters={filters}
        filterKey={filterKey}
        filterSearch={filterSearch}
        onClearFilters={() =>
          changeFilters({ fileCategories: [], extensions: [] })
        }
      />
    </section>
  )
}
