import type { ExactDuplicateOccurrence } from '../api/exactDuplicates.ts'

export interface DuplicatePhysicalCopy {
  fileEntryId: number
  contentRecordId: number
  presenceStatus: 'PRESENT' | 'MISSING'
  occurrences: ExactDuplicateOccurrence[]
}

export function groupPhysicalCopies(
  occurrences: ExactDuplicateOccurrence[],
): DuplicatePhysicalCopy[] {
  const copies = new Map<number, DuplicatePhysicalCopy>()
  for (const occurrence of occurrences) {
    if (occurrence.applicabilityStatus !== 'ACTIVE') continue
    const existing = copies.get(occurrence.fileEntryId)
    if (existing) {
      existing.occurrences.push(occurrence)
      if (occurrence.presenceStatus === 'PRESENT') existing.presenceStatus = 'PRESENT'
    } else {
      copies.set(occurrence.fileEntryId, {
        fileEntryId: occurrence.fileEntryId,
        contentRecordId: occurrence.contentRecordId,
        presenceStatus: occurrence.presenceStatus,
        occurrences: [occurrence],
      })
    }
  }
  return [...copies.values()]
}
