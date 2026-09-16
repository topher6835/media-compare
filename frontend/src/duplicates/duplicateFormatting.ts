import type { ExactDuplicateOccurrence } from '../api/exactDuplicates.ts'

export function duplicateReference(digestHex: string): string {
  return `DUP-${digestHex.slice(0, 8).toUpperCase()}`
}

export function formatBytes(bytes: number): string {
  if (bytes === 0) {
    return '0 B'
  }

  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']
  const unitIndex = Math.min(
    Math.floor(Math.log(bytes) / Math.log(1024)),
    units.length - 1,
  )
  const value = bytes / 1024 ** unitIndex
  const maximumFractionDigits = value >= 10 || unitIndex === 0 ? 0 : 1

  return `${value.toLocaleString(undefined, { maximumFractionDigits })} ${units[unitIndex]}`
}

export function filenameFromPath(relativePath: string): string {
  return relativePath.split('/').at(-1) ?? relativePath
}

function extensionFromPath(relativePath: string): string | null {
  const filename = filenameFromPath(relativePath)
  const lastDot = filename.lastIndexOf('.')
  if (lastDot <= 0 || lastDot === filename.length - 1) {
    return null
  }
  return filename.slice(lastDot + 1).toUpperCase()
}

export function occurrenceExtensions(
  occurrences: ExactDuplicateOccurrence[],
): string[] {
  const extensions = new Set<string>()
  let hasNoExtension = false

  for (const occurrence of occurrences) {
    const extension = extensionFromPath(occurrence.relativePath)
    if (extension) {
      extensions.add(extension)
    } else {
      hasNoExtension = true
    }
  }

  const result = [...extensions].sort()
  if (hasNoExtension) {
    result.push('No extension')
  }
  return result
}

export function pluralize(
  count: number,
  singular: string,
  plural = `${singular}s`,
): string {
  return `${count.toLocaleString()} ${count === 1 ? singular : plural}`
}
