import { managementRequest } from './startup.ts'

export interface Folder { name: string; path: string }
export interface DirectoryListing { path: string; parent: string | null; directories: Folder[]; truncated: boolean }
export interface SessionValidation {
  kind: 'VALID_SESSION' | 'DIRECTORY' | 'UNAVAILABLE' | 'INVALID_SESSION' | 'CANNOT_OPEN'
  message: string
}
export function getFilesystemRoots(signal?: AbortSignal): Promise<Folder[]> {
  return managementRequest('filesystem/roots', { signal })
}
export function getDirectories(path: string, filter = '', signal?: AbortSignal): Promise<DirectoryListing> {
  return managementRequest(`filesystem/directories?${new URLSearchParams({ path, filter })}`, { signal })
}
export function validateSession(path: string, signal?: AbortSignal): Promise<SessionValidation> {
  return managementRequest(`filesystem/validate?${new URLSearchParams({ path })}`, { signal })
}
