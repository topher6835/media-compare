export type TechnicalFileCategory = 'PHOTO' | 'VIDEO' | 'DOCUMENT'

export interface ExactDuplicateFilters {
  fileCategories: TechnicalFileCategory[]
  extensions: string[]
}

export interface ExactDuplicateFilterMatch {
  matchingOccurrenceCount: number
  matchingExtensions: string[]
}

export interface ExactDuplicateGroupSummary {
  digestHex: string
  sizeBytes: number
  contentRecordCount: number
  redundantContentRecordCount: number
  presentOccurrenceCount: number
  missingOccurrenceCount: number
  sourceCount: number
  potentialStorageSavingsBytes: number
  filterMatch: ExactDuplicateFilterMatch | null
}

export interface ExactDuplicateGroupPage {
  groups: ExactDuplicateGroupSummary[]
  nextAfterDigestHex: string | null
}

export interface ExactDuplicateMember {
  contentRecordId: number
  sizeBytes: number
}

export interface ExactDuplicateOccurrence {
  fileEntryId: number
  contentRecordId: number
  sourceId: number
  sourceName: string
  relativePath: string
  presenceStatus: 'PRESENT' | 'MISSING'
  extension: string | null
  fileCategory: TechnicalFileCategory | null
  matchesFilter: boolean
}

export interface ExactDuplicateGroupDetail
  extends Omit<ExactDuplicateGroupSummary, 'filterMatch'> {
  members: ExactDuplicateMember[]
  occurrences: ExactDuplicateOccurrence[]
}

export interface ExactDuplicateFilterOption {
  extension: string
  fileCategory: TechnicalFileCategory | null
  exactDuplicateGroupCount: number
  retainedOccurrenceCount: number
}

export interface ExactDuplicateFilterOptions {
  extensions: ExactDuplicateFilterOption[]
}

const supportedCategories = new Set<TechnicalFileCategory>([
  'PHOTO',
  'VIDEO',
  'DOCUMENT',
])

function uniqueSorted(values: string[]): string[] {
  return [...new Set(values)].sort()
}

export function canonicalizeExactDuplicateFilters(
  filters: ExactDuplicateFilters,
): ExactDuplicateFilters {
  return {
    fileCategories: uniqueSorted(filters.fileCategories).filter(
      (category): category is TechnicalFileCategory =>
        supportedCategories.has(category as TechnicalFileCategory),
    ),
    extensions: uniqueSorted(
      filters.extensions.map((extension) => extension.toUpperCase()),
    ),
  }
}

export function parseExactDuplicateFilters(
  parameters: URLSearchParams,
): ExactDuplicateFilters {
  const fileCategories = parameters
    .getAll('fileCategory')
    .map((category) => category.toUpperCase())
    .filter((category): category is TechnicalFileCategory =>
      supportedCategories.has(category as TechnicalFileCategory),
    )
  const extensions = parameters
    .getAll('extension')
    .map((extension) => extension.toUpperCase())

  return canonicalizeExactDuplicateFilters({ fileCategories, extensions })
}

export function exactDuplicateFilterParameters(
  filters: ExactDuplicateFilters,
): URLSearchParams {
  const parameters = new URLSearchParams()
  const canonical = canonicalizeExactDuplicateFilters(filters)
  canonical.fileCategories.forEach((category) =>
    parameters.append('fileCategory', category),
  )
  canonical.extensions.forEach((extension) =>
    parameters.append('extension', extension),
  )
  return parameters
}

export function exactDuplicateFilterSearch(
  filters: ExactDuplicateFilters,
): string {
  const query = exactDuplicateFilterParameters(filters).toString()
  return query ? `?${query}` : ''
}

export function exactDuplicateFilterKey(filters: ExactDuplicateFilters): string {
  return exactDuplicateFilterParameters(filters).toString()
}

export function hasExactDuplicateFilters(
  filters: ExactDuplicateFilters,
): boolean {
  return filters.fileCategories.length > 0 || filters.extensions.length > 0
}

export class ApiError extends Error {
  readonly status: number

  constructor(status: number) {
    super(`Request failed with status ${status}`)
    this.name = 'ApiError'
    this.status = status
  }
}

async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url, {
    headers: { Accept: 'application/json' },
  })

  if (!response.ok) {
    throw new ApiError(response.status)
  }

  return (await response.json()) as T
}

export function getExactDuplicateGroups(
  filters: ExactDuplicateFilters,
  afterDigestHex?: string,
): Promise<ExactDuplicateGroupPage> {
  const parameters = exactDuplicateFilterParameters(filters)
  parameters.set('limit', '50')
  if (afterDigestHex) {
    parameters.set('afterDigestHex', afterDigestHex)
  }

  return getJson<ExactDuplicateGroupPage>(
    `/api/exact-duplicate-groups?${parameters.toString()}`,
  )
}

export function getExactDuplicateGroup(
  digestHex: string,
  filters: ExactDuplicateFilters,
): Promise<ExactDuplicateGroupDetail> {
  const query = exactDuplicateFilterParameters(filters).toString()
  return getJson<ExactDuplicateGroupDetail>(
    `/api/exact-duplicate-groups/${encodeURIComponent(digestHex)}${query ? `?${query}` : ''}`,
  )
}

export function getExactDuplicateFilterOptions(): Promise<ExactDuplicateFilterOptions> {
  return getJson<ExactDuplicateFilterOptions>(
    '/api/exact-duplicate-groups/filter-options',
  )
}
