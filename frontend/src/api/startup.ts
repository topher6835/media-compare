export type StartupMode = 'management' | 'catalog'
const catalogStates = ['NOT_STARTED', 'CREATED', 'STARTING', 'RUNNING', 'STOPPING', 'FAILED']

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
      !('catalogState' in status) || typeof status.catalogState !== 'string' || !catalogStates.includes(status.catalogState)) {
    throw new Error('Unsupported management status')
  }
  return 'management'
}
