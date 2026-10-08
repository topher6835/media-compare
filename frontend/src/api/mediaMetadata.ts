import type { SourceAuthorityWindow } from './sources.ts'
import { requestJson } from './http.ts'

export interface MediaMetadataRun {
  jobId: number
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  createdAtMs: number
  errorMessage: string | null
  stage: {
    candidatesAttempted: number
    result: {
      completedAvailable: number
      completedUnsupported: number
      failed: number
      staleOrUnavailable: number
      completedWithIssues: boolean
    } | null
  }
}

export interface MediaMetadataStatus {
  active: MediaMetadataRun | null
  latest: MediaMetadataRun | null
}

export function getMediaMetadataStatus(signal?: AbortSignal): Promise<MediaMetadataStatus> {
  return requestJson('/api/media-metadata-runs/status', { signal })
}

export type MetadataOwnership = { indexingScanRunId: number } | { authorityWindows: SourceAuthorityWindow[] }

export function getMediaMetadataRun(jobId: number, signal?: AbortSignal): Promise<MediaMetadataRun> {
  return requestJson(`/api/media-metadata-runs/${jobId}`, { signal })
}

export function startMediaMetadataRun(ownership?: MetadataOwnership): Promise<MediaMetadataRun> {
  return requestJson('/api/media-metadata-runs', { method: 'POST',
    ...(ownership ? { body: JSON.stringify(ownership) } : {}) })
}
