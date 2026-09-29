import { requestJson } from './http.ts'

export type GenerationSupport = 'SUPPORTED' | 'UNSUPPORTED'
export type ThumbnailState = 'MISSING' | 'PUBLISHED'
export type ThumbnailScheduleStatus = 'QUEUED' | 'ALREADY_QUEUED' | 'QUEUE_FULL'

export type ThumbnailReference =
  | { state: 'MISSING'; assetKey: null; url: null; width: null; height: null }
  | { state: 'PUBLISHED'; assetKey: string; url: string; width: number; height: number }

export interface MediaLibraryItem {
  fileEntryId: number
  contentRecordId: number
  sourceId: number
  sourceName: string
  relativePath: string
  displayName: string
  extensionKey: string | null
  sizeBytes: number
  format: string
  encodedWidth: number
  encodedHeight: number
  sourceCount: number
  generationSupport: GenerationSupport
  thumbnail: ThumbnailReference
}

export interface MediaLibraryPage {
  items: MediaLibraryItem[]
  nextCursor: number | null
}

export interface MediaLibraryGroupSummary {
  groupKeyContentRecordId: number
  representative: MediaLibraryItem
  currentItemCount: number
}

export interface MediaLibraryGroupPage {
  groups: MediaLibraryGroupSummary[]
  nextCursor: number | null
}

export interface ThumbnailScheduleResult {
  fileEntryId: number
  status: ThumbnailScheduleStatus
}

export interface ThumbnailScheduleResponse {
  results: ThumbnailScheduleResult[]
}

export function getMediaLibraryItems(
  afterFileEntryId?: number | null,
  signal?: AbortSignal,
): Promise<MediaLibraryPage> {
  const parameters = new URLSearchParams({ limit: '50' })
  if (afterFileEntryId !== undefined && afterFileEntryId !== null) {
    parameters.set('afterFileEntryId', String(afterFileEntryId))
  }
  return requestJson(`/api/media-library/items?${parameters}`, { signal })
}

export function getMediaLibraryGroups(
  afterRepresentativeFileEntryId?: number | null,
  signal?: AbortSignal,
): Promise<MediaLibraryGroupPage> {
  const parameters = new URLSearchParams({ relationshipType: 'EXACT', limit: '50' })
  if (afterRepresentativeFileEntryId !== undefined && afterRepresentativeFileEntryId !== null) {
    parameters.set('afterRepresentativeFileEntryId', String(afterRepresentativeFileEntryId))
  }
  return requestJson(`/api/media-library/groups?${parameters}`, { signal })
}

export function chunkThumbnailIds(fileEntryIds: number[]): number[][] {
  const chunks: number[][] = []
  for (let offset = 0; offset < fileEntryIds.length; offset += 100) {
    chunks.push(fileEntryIds.slice(offset, offset + 100))
  }
  return chunks
}

export async function scheduleMediaLibraryThumbnails(
  fileEntryIds: number[],
  signal?: AbortSignal,
): Promise<ThumbnailScheduleResponse> {
  const results: ThumbnailScheduleResult[] = []
  for (const ids of chunkThumbnailIds(fileEntryIds)) {
    const response = await requestJson<ThumbnailScheduleResponse>(
      '/api/media-library/thumbnails',
      { method: 'POST', body: JSON.stringify({ fileEntryIds: ids }), signal },
    )
    results.push(...response.results)
  }
  return { results }
}
