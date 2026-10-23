export type StartupMode = 'management' | 'catalog'

export async function getStartupMode(signal?: AbortSignal): Promise<StartupMode> {
  const response = await fetch('/api/management/status', {
    headers: { Accept: 'application/json' },
    cache: 'no-store',
    signal,
  })
  // The existing Session-bound backend has no management endpoint.
  if (response.status === 404) return 'catalog'
  if (!response.ok) throw new Error('Management status is unavailable')

  const status: unknown = await response.json()
  if (typeof status !== 'object' || status === null ||
      !('service' in status) || status.service !== 'media-compare-management' ||
      !('catalogState' in status) || status.catalogState !== 'NOT_STARTED') {
    throw new Error('Unsupported management status')
  }
  return 'management'
}
