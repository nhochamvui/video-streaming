export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly retryAfterSeconds?: number,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

function parseRetryAfter(response: Response): number | undefined {
  const header = response.headers.get('Retry-After')
  if (!header) return undefined
  const seconds = parseInt(header, 10)
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : undefined
}

export async function apiFetch<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'same-origin', ...init })
  const body = await response.json().catch(() => ({}))
  if (!response.ok) {
    const message =
      typeof body?.error === 'string' ? body.error : `Request failed (${response.status})`
    const retryAfterSeconds =
      parseRetryAfter(response) ??
      (typeof body?.retryAfterSeconds === 'number' ? body.retryAfterSeconds : undefined)
    throw new ApiError(message, response.status, retryAfterSeconds)
  }
  return body as T
}
