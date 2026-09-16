import type {
  ExactDuplicateGroupPage,
  ExactDuplicateGroupSummary,
} from '../api/exactDuplicates.ts'

interface DuplicateTrailEntry {
  digestHex: string
  reference: string
}

interface DuplicateListMemory {
  filterKey: string
  groups: ExactDuplicateGroupSummary[]
  nextAfterDigestHex: string | null
  hasLoaded: boolean
  scrollY: number
}

const listMemory: DuplicateListMemory = {
  filterKey: '',
  groups: [],
  nextAfterDigestHex: null,
  hasLoaded: false,
  scrollY: 0,
}

const recentDuplicateVisits: DuplicateTrailEntry[] = []
const MAX_TRAIL_ENTRIES = 8

export function getDuplicateListMemory(filterKey: string): DuplicateListMemory {
  if (listMemory.filterKey !== filterKey) {
    return {
      filterKey,
      groups: [],
      nextAfterDigestHex: null,
      hasLoaded: false,
      scrollY: 0,
    }
  }
  return {
    ...listMemory,
    groups: [...listMemory.groups],
  }
}

export function rememberDuplicatePage(
  filterKey: string,
  page: ExactDuplicateGroupPage,
  append: boolean,
): ExactDuplicateGroupSummary[] {
  const groupsByDigest = new Map<string, ExactDuplicateGroupSummary>()

  if (append && listMemory.filterKey === filterKey) {
    for (const group of listMemory.groups) {
      groupsByDigest.set(group.digestHex, group)
    }
  }
  for (const group of page.groups) {
    groupsByDigest.set(group.digestHex, group)
  }

  listMemory.filterKey = filterKey
  listMemory.groups = [...groupsByDigest.values()]
  listMemory.nextAfterDigestHex = page.nextAfterDigestHex
  listMemory.hasLoaded = true
  return [...listMemory.groups]
}

export function rememberDuplicateListScroll(
  filterKey: string,
  scrollY: number,
): void {
  if (listMemory.filterKey === filterKey) {
    listMemory.scrollY = scrollY
  }
}

export function recordDuplicateVisit(entry: DuplicateTrailEntry): void {
  const lastEntry = recentDuplicateVisits.at(-1)
  if (lastEntry?.digestHex === entry.digestHex) {
    return
  }

  recentDuplicateVisits.push(entry)
  if (recentDuplicateVisits.length > MAX_TRAIL_ENTRIES) {
    recentDuplicateVisits.shift()
  }
}

export function getDuplicateTrail(): DuplicateTrailEntry[] {
  return [...recentDuplicateVisits]
}
