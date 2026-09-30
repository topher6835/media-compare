import { Link } from 'react-router-dom'
import type { MediaLibraryItem } from '../api/mediaLibrary.ts'

export function ExactBadge({ exactSet }: { exactSet: MediaLibraryItem['exactSet'] }) {
  if (!exactSet || exactSet.physicalCopyCount < 2) return null
  return <Link className="exact-badge" to={`/duplicates/${exactSet.digestHex}`}
    aria-label={`View exact duplicate set with ${exactSet.physicalCopyCount} physical copies`}>
    Exact ×{exactSet.physicalCopyCount}
  </Link>
}
