/** One browser document attaches to one managed catalog generation. A different Session requires reload. */
let access: string | null = null
let catalogId: string | null = null
let directRuntimeId: string | null = null

export function identifyDirectCatalogRuntime(value: string): void {
  if (directRuntimeId !== null && directRuntimeId !== value) throw new Error('The catalog changed. Reload to continue.')
  directRuntimeId = value
}
export function directCatalogRuntimeId(): string | null { return directRuntimeId }

export function setManagementAccess(value: string): void { access = value }
export function attachCatalog(value: string): void {
  if (catalogId !== null && catalogId !== value) throw new Error('The active Session changed. Reload to continue.')
  if (typeof window !== 'undefined') {
    let previous: string | null = null
    try {
      previous = window.sessionStorage.getItem('media-compare-catalog')
      window.sessionStorage.setItem('media-compare-catalog', value)
    } catch {
      // Without storage, conservatively return unscoped catalog deep links to home on every reload.
    }
    if (previous !== value && (window.location.pathname !== '/' || window.location.search)) {
      window.location.replace('/')
      throw new Error('The active Session changed or is unrecognized. Returning to the catalog home…')
    }
  }
  catalogId = value
}
export function currentCatalogId(): string | null { return catalogId }

export function managementHeaders(): Headers {
  const headers = new Headers({ Accept: 'application/json' })
  if (access !== null) headers.set('X-Media-Compare-Access', access)
  return headers
}

export function catalogAssetUrl(url: string): string {
  if (catalogId === null) return url
  return `${url}${url.includes('?') ? '&' : '?'}catalogId=${encodeURIComponent(catalogId)}`
}

export function catalogFetch(url: string, init?: RequestInit): Promise<Response> {
  if (access === null) return fetch(url, init)
  const headers = new Headers(init?.headers)
  if (access !== null) {
    if (catalogId === null) return Promise.reject(new Error('No managed catalog is ready.'))
    headers.set('X-Media-Compare-Access', access)
    headers.set('X-Media-Compare-Catalog', catalogId)
  }
  // Headers are captured before dispatch; delayed requests can never borrow a later generation.
  return fetch(url, { ...init, headers })
}
