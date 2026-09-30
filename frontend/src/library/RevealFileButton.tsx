import { useEffect, useRef, useState } from 'react'
import { revealMediaLibraryFile } from '../api/mediaLibrary.ts'
import { revealFailureMessage } from './revealStatus.ts'

export function RevealFileButton({ fileEntryId }: { fileEntryId: number }) {
  const [pending, setPending] = useState(false)
  const [message, setMessage] = useState('')
  const request = useRef<AbortController | null>(null)

  useEffect(() => () => request.current?.abort(), [])

  async function reveal() {
    if (request.current) return
    const controller = new AbortController()
    request.current = controller
    setPending(true)
    setMessage('')
    try {
      await revealMediaLibraryFile(fileEntryId, controller.signal)
    } catch (error) {
      if (controller.signal.aborted) return
      setMessage(revealFailureMessage(error))
    } finally {
      request.current = null
      if (!controller.signal.aborted) setPending(false)
    }
  }

  return <span className="reveal-control">
    <button type="button" disabled={pending} onClick={() => void reveal()}
      aria-label={`Reveal FileEntry ${fileEntryId} in Finder`}>
      {pending ? 'Revealing…' : 'Reveal in Finder'}
    </button>
    {message && <span className="reveal-message" role="alert">{message}</span>}
  </span>
}
