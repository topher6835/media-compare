import type {
  CleanupPreflightReason,
  CleanupPreflightResponse,
} from '../api/exactDuplicates.ts'

// Page component state only; never part of the saved cleanup plan.
export type CleanupSafetyState =
  | { status: 'NOT_CHECKED' | 'CHECKING' | 'CHECK_FAILED' }
  | { status: 'READY' | 'BLOCKED'; result: CleanupPreflightResponse }

export const cleanupSafetyLabels: Record<CleanupSafetyState['status'], string> = {
  NOT_CHECKED: 'NOT CHECKED',
  CHECKING: 'CHECKING',
  CHECK_FAILED: 'CHECK FAILED',
  READY: 'READY NOW',
  BLOCKED: 'BLOCKED',
}

const reasonMessages: Record<CleanupPreflightReason, string> = {
  GROUP_CHANGED: 'The exact duplicate group changed; review it again.',
  KEEPER_UNAVAILABLE: 'The planned keeper is no longer currently available.',
  CANDIDATE_SET_CHANGED: 'Current present copies no longer match the saved plan.',
  AUTHORITY_UNAVAILABLE: 'Current storage authority could not be verified.',
  AUTHORITY_CHANGED: 'Source or storage authority changed.',
  FILESYSTEM_CHANGED: 'File metadata or identity changed.',
  UNSAFE_PATH: 'The path or file structure no longer meets cleanup safety requirements.',
  HASH_MISMATCH: 'Current file bytes no longer match the exact duplicate digest.',
  IO_UNAVAILABLE: 'The file could not currently be read or inspected.',
}

export function cleanupSafetyReasonMessage(reason: CleanupPreflightReason | null): string {
  return reason === null ? 'Safety requirements were not met; review the group again.' : reasonMessages[reason]
}

export function findCleanupFileResult(result: CleanupPreflightResponse | undefined, fileEntryId: number) {
  if (result?.keeper.fileEntryId === fileEntryId) return result.keeper
  return result?.candidates.find((candidate) => candidate.fileEntryId === fileEntryId)
}

export function invalidateCleanupSafetyResults(
  digests: string[],
  states: Record<string, CleanupSafetyState>,
): Record<string, CleanupSafetyState> {
  const nextStates = { ...states }
  for (const digest of digests) {
    nextStates[digest] = { status: 'NOT_CHECKED' }
  }
  return nextStates
}

export function aggregateCleanupSafety(
  digests: string[],
  states: Record<string, CleanupSafetyState>,
) {
  const summary = { readyNow: 0, blocked: 0, notChecked: 0, failed: 0, checking: 0 }
  for (const digest of digests) {
    switch (states[digest]?.status) {
      case 'READY': summary.readyNow++; break
      case 'BLOCKED': summary.blocked++; break
      case 'CHECK_FAILED': summary.failed++; break
      case 'CHECKING': summary.checking++; break
      default: summary.notChecked++
    }
  }
  return summary
}
