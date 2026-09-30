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

export function startMediaMetadataRun(): Promise<MediaMetadataRun> {
  return requestJson('/api/media-metadata-runs', { method: 'POST' })
}
