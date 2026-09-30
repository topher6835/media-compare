import { ApiError } from '../api/http.ts'

export function revealFailureMessage(error: unknown): string {
  if (error instanceof ApiError && [404, 409, 410].includes(error.status)) {
    return 'File could not be revealed because its current location could not be verified.'
  }
  if (error instanceof ApiError && error.status === 501) {
    return 'Reveal in Finder is available on macOS only.'
  }
  return 'Reveal request failed. Check that the backend is running and try again.'
}
