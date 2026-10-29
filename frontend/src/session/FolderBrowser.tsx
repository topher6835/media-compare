import { useEffect, useState, type FormEvent } from 'react'
import { getDirectories, getFilesystemRoots, validateSession, type DirectoryListing, type Folder, type SessionValidation } from '../api/filesystem.ts'

interface Props {
  mode: 'open' | 'new'
  onSelect: (path: string) => void
  onCancel: () => void
  busy?: boolean
}

/** Browsing and selection are read-only in both modes. */
export function FolderBrowser({ mode, onSelect, onCancel, busy = false }: Props) {
  const [roots, setRoots] = useState<Folder[]>([])
  const [listing, setListing] = useState<DirectoryListing | null>(null)
  const [pathInput, setPathInput] = useState('')
  const [target, setTarget] = useState<{ path: string; filter: string } | null>(null)
  const [filter, setFilter] = useState('')
  const [validation, setValidation] = useState<SessionValidation | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [rootsAttempt, setRootsAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    getFilesystemRoots(controller.signal).then((nextRoots) => {
      if (controller.signal.aborted) return
      setRoots(nextRoots)
      if (mode === 'new') {
        const home = nextRoots.find((root) => root.name === 'Home')
        if (home) {
          setTarget((current) => current ?? { path: home.path, filter: '' })
        }
      }
    })
      .catch((failure: unknown) => { if (!controller.signal.aborted) setError(failure instanceof Error ? failure.message : 'Locations are unavailable.') })
    return () => controller.abort()
  }, [rootsAttempt, mode])

  useEffect(() => {
    if (!target) return
    const controller = new AbortController()
    Promise.all([getDirectories(target.path, target.filter, controller.signal), mode === 'open' ? validateSession(target.path, controller.signal) : Promise.resolve(null)])
      .then(([nextListing, nextValidation]) => {
        if (controller.signal.aborted) return
        setListing(nextListing)
        setPathInput(nextListing.path)
        setValidation(nextValidation)
        setLoading(false)
      }).catch((failure: unknown) => {
        if (controller.signal.aborted) return
        setError(failure instanceof Error ? failure.message : 'The folder is unavailable.')
        setLoading(false)
      })
    return () => controller.abort()
  }, [target, mode])

  function navigate(path: string, nextFilter = '') {
    setLoading(true)
    setListing(null)
    setValidation(null)
    setError(null)
    setFilter(nextFilter)
    setTarget({ path, filter: nextFilter })
  }
  function enterPath(event: FormEvent) { event.preventDefault(); navigate(pathInput) }
  const selectable = !loading && listing !== null && (mode === 'new' || validation?.kind === 'VALID_SESSION')

  return <section className="folder-browser" aria-labelledby="folder-browser-title" aria-busy={loading || busy}>
    <h2 id="folder-browser-title">{mode === 'open' ? 'Open Existing Session' : 'Choose a new Session location'}</h2>
    <fieldset disabled={busy}>
      <legend>Locations</legend>
      <div className="folder-actions">
        {roots.map((root) => <button key={root.path} type="button" onClick={() => navigate(root.path)}>{root.name}</button>)}
        <button type="button" onClick={() => setRootsAttempt((value) => value + 1)}>Refresh locations</button>
      </div>
      <p className="startup-note">Choose Home or a drive to browse. On macOS, choose / and then Volumes for mounted external and network drives. On Windows, mapped drives appear here; you can also enter an accessible network path.</p>
      <form onSubmit={enterPath} className="folder-path-form">
        <label htmlFor="folder-path">Absolute folder path</label>
        <input id="folder-path" value={pathInput} onChange={(event) => setPathInput(event.target.value)} autoComplete="off" spellCheck={false} required />
        <button type="submit">Go</button>
      </form>
      {loading && <p role="status">Reading folder…</p>}
      {error && <p role="alert">{error}</p>}
      {listing && <>
        <p>Current location: <strong className="folder-current-path">{listing.path}</strong></p>
        <div className="folder-actions">
          <button type="button" disabled={!listing.parent} onClick={() => { if (listing.parent) navigate(listing.parent) }}>Parent folder</button>
          <button type="button" onClick={() => navigate(listing.path, filter)}>Refresh folder</button>
        </div>
        <form onSubmit={(event) => { event.preventDefault(); navigate(listing.path, filter) }} className="folder-path-form">
          <label htmlFor="folder-filter">Filter folder names</label>
          <input id="folder-filter" value={filter} onChange={(event) => setFilter(event.target.value)} />
          <button type="submit">Filter</button>
        </form>
        <ul className="folder-list" aria-label="Subdirectories">
          {listing.directories.map((folder) => <li key={folder.path}><button type="button" onClick={() => navigate(folder.path)}>{folder.name}</button></li>)}
        </ul>
        {listing.directories.length === 0 && <p>No matching subdirectories.</p>}
        {listing.truncated && <p role="status">This listing reached its limit. Filter names, or enter the full path to reach another folder.</p>}
        {validation && <p role="status"><strong>{validation.kind === 'VALID_SESSION' ? 'Media Compare Session' : validation.kind === 'DIRECTORY' ? 'Ordinary folder' : 'Cannot open Session'}</strong>: {validation.message}</p>}
      </>}
      {mode === 'new' && <p>Select an existing parent folder. Nothing is created while browsing.</p>}
      <div className="folder-actions">
        <button type="button" disabled={!selectable} onClick={() => { if (listing) onSelect(listing.path) }}>{mode === 'open' ? 'Open this Session' : 'Select folder'}</button>
        <button type="button" onClick={onCancel}>Cancel</button>
      </div>
    </fieldset>
  </section>
}
