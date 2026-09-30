import type { MediaMetadataRun, MediaMetadataStatus } from '../api/mediaMetadata.ts'

export type MetadataAction = 'start' | 'poll' | 'complete' | 'failed'

export function metadataAction(
  indexingFinishedAtMs: number,
  status: MediaMetadataStatus,
): MetadataAction {
  if (status.active) return 'poll'
  const latest = status.latest
  if (!latest || latest.createdAtMs < indexingFinishedAtMs) return 'start'
  return latest.status === 'COMPLETED' ? 'complete' : 'failed'
}

export function metadataHasIssues(run: MediaMetadataRun): boolean {
  const result = run.stage.result
  return result !== null && (result.completedWithIssues || result.completedUnsupported > 0)
}
