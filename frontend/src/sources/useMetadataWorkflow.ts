import { useEffect, useState } from 'react'
import { ApiError } from '../api/http.ts'
import { getMediaMetadataStatus, startMediaMetadataRun, type MediaMetadataRun } from '../api/mediaMetadata.ts'
import { metadataAction } from './metadataWorkflow.ts'

const pollIntervalMs = 1500

export interface MetadataPhase {
  scanRunId: number
  state: 'checking' | 'running' | 'complete' | 'failed' | 'unavailable'
  run: MediaMetadataRun | null
}

export function useMetadataWorkflow(scanRunId: number | null, finishedAtMs: number | null) {
  const [phase, setPhase] = useState<MetadataPhase | null>(null)
  const [retry, setRetry] = useState<{ scanRunId: number; count: number } | null>(null)

  useEffect(() => {
    if (scanRunId === null || finishedAtMs === null) return
    const controller = new AbortController()
    let timer: number | undefined
    let forceRetry = retry?.scanRunId === scanRunId

    async function check() {
      try {
        const status = await getMediaMetadataStatus(controller.signal)
        if (controller.signal.aborted) return
        const action = metadataAction(finishedAtMs!, status)
        if (action === 'complete' || (action === 'failed' && !forceRetry)) {
          setPhase({ scanRunId: scanRunId!, state: action, run: status.latest })
          return
        }
        if (action === 'poll') {
          setPhase({ scanRunId: scanRunId!, state: 'running', run: status.active })
          timer = window.setTimeout(check, pollIntervalMs)
          return
        }
        // A retry is explicit. A normal start happens only when no newer durable pass exists.
        forceRetry = false
        setPhase({ scanRunId: scanRunId!, state: 'running', run: null })
        try {
          await startMediaMetadataRun()
        } catch (error: unknown) {
          if (!(error instanceof ApiError && (error.status === 409 || error.status === 503))) {
            // The request outcome may be uncertain. Read durable status before any new POST.
            if (!controller.signal.aborted) {
              setPhase({ scanRunId: scanRunId!, state: 'unavailable', run: null })
            }
          }
        }
        if (!controller.signal.aborted) timer = window.setTimeout(check, pollIntervalMs)
      } catch {
        if (controller.signal.aborted) return
        setPhase({ scanRunId: scanRunId!, state: 'unavailable', run: null })
        timer = window.setTimeout(check, pollIntervalMs)
      }
    }

    void check()
    return () => {
      controller.abort()
      if (timer !== undefined) window.clearTimeout(timer)
    }
  }, [scanRunId, finishedAtMs, retry])

  return {
    phase: phase?.scanRunId === scanRunId ? phase : null,
    retryMetadata: () => {
      if (scanRunId !== null) setRetry((value) => ({ scanRunId, count: (value?.count ?? 0) + 1 }))
    },
  }
}
