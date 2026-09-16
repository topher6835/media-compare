export interface ExactDuplicateGroupSummary {
  digestHex: string
  sizeBytes: number
  contentRecordCount: number
  redundantContentRecordCount: number
  presentOccurrenceCount: number
  missingOccurrenceCount: number
  sourceCount: number
  potentialStorageSavingsBytes: number
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
}

export interface ExactDuplicateGroupDetail extends ExactDuplicateGroupSummary {
  members: ExactDuplicateMember[]
  occurrences: ExactDuplicateOccurrence[]
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
  afterDigestHex?: string,
): Promise<ExactDuplicateGroupPage> {
  const parameters = new URLSearchParams({ limit: '50' })
  if (afterDigestHex) {
    parameters.set('afterDigestHex', afterDigestHex)
  }

  return getJson<ExactDuplicateGroupPage>(
    `/api/exact-duplicate-groups?${parameters.toString()}`,
  )
}

export function getExactDuplicateGroup(
  digestHex: string,
): Promise<ExactDuplicateGroupDetail> {
  return getJson<ExactDuplicateGroupDetail>(
    `/api/exact-duplicate-groups/${encodeURIComponent(digestHex)}`,
  )
}
