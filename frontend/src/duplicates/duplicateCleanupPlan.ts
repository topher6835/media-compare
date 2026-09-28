import type { ExactDuplicateGroupDetail } from '../api/exactDuplicates.ts'
import { filenameFromPath, formatBytes } from './duplicateFormatting.ts'
import { groupPhysicalCopies, type DuplicatePhysicalCopy } from './duplicatePhysicalCopies.ts'

export interface DuplicateCleanupCopySnapshot {
  fileEntryId: number
  label: string
  sourcePaths: Array<{
    sourceId: number
    sourceName: string
    relativePath: string
    presenceStatus: 'PRESENT' | 'MISSING'
  }>
}

// Session review snapshots only; these are never filesystem authority.
export interface DuplicateCleanupPlanEntry {
  digestHex: string
  keeper: DuplicateCleanupCopySnapshot
  candidates: DuplicateCleanupCopySnapshot[]
  sizeBytes: number
  candidateCount: number
  estimatedSavingsBytes: number | null
}

const cleanupPlan = new Map<string, DuplicateCleanupPlanEntry>()
const MAX_SAFE_BYTES = BigInt(Number.MAX_SAFE_INTEGER)

export function estimateCleanupSavings(candidateCount: number, sizeBytes: number): number | null {
  if (!Number.isSafeInteger(candidateCount) || candidateCount < 0 ||
      !Number.isSafeInteger(sizeBytes) || sizeBytes < 0) return null
  const savings = BigInt(candidateCount) * BigInt(sizeBytes)
  return savings <= MAX_SAFE_BYTES ? Number(savings) : null
}

export function formatCleanupSavings(bytes: number | null): string {
  return bytes === null ? 'Estimate unavailable' : formatBytes(bytes)
}

function copySnapshot(copy: DuplicateCleanupCopySnapshot): DuplicateCleanupCopySnapshot {
  return { ...copy, sourcePaths: copy.sourcePaths.map((path) => ({ ...path })) }
}

function copyEntry(entry: DuplicateCleanupPlanEntry): DuplicateCleanupPlanEntry {
  return {
    ...entry,
    keeper: copySnapshot(entry.keeper),
    candidates: entry.candidates.map(copySnapshot),
  }
}

export function getCleanupPlan(): DuplicateCleanupPlanEntry[] {
  return [...cleanupPlan.values()].map(copyEntry)
}

export function getCleanupPlanEntry(digestHex: string): DuplicateCleanupPlanEntry | undefined {
  const entry = cleanupPlan.get(digestHex)
  return entry ? copyEntry(entry) : undefined
}

export function saveCleanupPlanEntry(entry: DuplicateCleanupPlanEntry): void {
  const candidates = new Map<number, DuplicateCleanupCopySnapshot>()
  for (const candidate of entry.candidates) {
    if (candidate.fileEntryId !== entry.keeper.fileEntryId) {
      candidates.set(candidate.fileEntryId, candidate)
    }
  }
  const candidateCount = candidates.size
  cleanupPlan.set(entry.digestHex, copyEntry({
    ...entry,
    candidates: [...candidates.values()],
    candidateCount,
    estimatedSavingsBytes: estimateCleanupSavings(candidateCount, entry.sizeBytes),
  }))
}

export function removeCleanupPlanEntry(digestHex: string): void {
  cleanupPlan.delete(digestHex)
}

export function clearCleanupPlan(): void {
  cleanupPlan.clear()
}

function snapshotPhysicalCopy(copy: DuplicatePhysicalCopy): DuplicateCleanupCopySnapshot {
  return {
    fileEntryId: copy.fileEntryId,
    label: filenameFromPath(copy.occurrences[0].relativePath),
    sourcePaths: copy.occurrences.map((occurrence) => ({
      sourceId: occurrence.sourceId,
      sourceName: occurrence.sourceName,
      relativePath: occurrence.relativePath,
      presenceStatus: occurrence.presenceStatus,
    })),
  }
}

export function createCleanupPlanEntry(
  detail: ExactDuplicateGroupDetail,
  keeperId: number,
): DuplicateCleanupPlanEntry | null {
  const presentCopies = groupPhysicalCopies(detail.occurrences)
    .filter((copy) => copy.presenceStatus === 'PRESENT')
  const keeper = presentCopies.find((copy) => copy.fileEntryId === keeperId)
  if (!keeper || presentCopies.length < 2) return null
  const candidates = presentCopies.filter((copy) => copy.fileEntryId !== keeperId)
  return {
    digestHex: detail.digestHex,
    keeper: snapshotPhysicalCopy(keeper),
    candidates: candidates.map(snapshotPhysicalCopy),
    sizeBytes: detail.sizeBytes,
    candidateCount: candidates.length,
    estimatedSavingsBytes: estimateCleanupSavings(candidates.length, detail.sizeBytes),
  }
}

// Called only after a fresh detail read, never from a cached list or filtered subset.
export function invalidateUnavailableCleanupKeeper(detail: ExactDuplicateGroupDetail): boolean {
  const entry = getCleanupPlanEntry(detail.digestHex)
  if (!entry || createCleanupPlanEntry(detail, entry.keeper.fileEntryId)) return false
  removeCleanupPlanEntry(detail.digestHex)
  return true
}

export function aggregateCleanupPlan(entries: DuplicateCleanupPlanEntry[]) {
  let candidateCount = 0
  let savings = 0n
  let estimateAvailable = true
  for (const entry of entries) {
    const count = new Set(entry.candidates.map((copy) => copy.fileEntryId)).size
    candidateCount += count
    const groupSavings = estimateCleanupSavings(count, entry.sizeBytes)
    if (groupSavings === null) estimateAvailable = false
    else savings += BigInt(groupSavings)
  }
  return {
    groupCount: entries.length,
    keeperCount: entries.length,
    candidateCount,
    estimatedSavingsBytes: estimateAvailable && savings <= MAX_SAFE_BYTES ? Number(savings) : null,
  }
}
