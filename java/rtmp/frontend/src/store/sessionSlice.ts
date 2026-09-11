import { createAction, createAsyncThunk, createSlice } from '@reduxjs/toolkit'
import { ApiError } from '../api/client'
import { createStreamSession } from '../api/session'
import type { StreamSession } from '../api/types'

const MAX_RETRY_WINDOW_MS = 120_000
const RETRYABLE_STATUSES = [429, 503]

interface SessionState {
  status: 'idle' | 'loading' | 'waiting' | 'success' | 'error'
  error: string
  statusCode: number | null
  session: StreamSession | null
  expiresAt: number | null
}

const initialState: SessionState = {
  status: 'idle',
  error: '',
  statusCode: null,
  session: null,
  expiresAt: null,
}

export const waitingForCapacity = createAction('session/waitingForCapacity')

function retryDelayMs(attempt: number, retryAfterSeconds?: number): number {
  if (retryAfterSeconds && retryAfterSeconds > 0) {
    return Math.min(retryAfterSeconds * 1000, MAX_RETRY_WINDOW_MS)
  }
  const backoff = Math.min(Math.pow(2, attempt) * 1000, MAX_RETRY_WINDOW_MS)
  return backoff + Math.floor(Math.random() * 500)
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export const createSession = createAsyncThunk('session/create', async (_, { dispatch, rejectWithValue }) => {
  const deadline = Date.now() + MAX_RETRY_WINDOW_MS
  let attempt = 0
  for (;;) {
    try {
      return await createStreamSession()
    } catch (error) {
      const status = error instanceof ApiError ? error.status : null
      const message = error instanceof Error ? error.message : 'Could not create stream session'
      if (status === null || !RETRYABLE_STATUSES.includes(status)) {
        return rejectWithValue({ status, message })
      }
      const delay = retryDelayMs(attempt, error instanceof ApiError ? error.retryAfterSeconds : undefined)
      if (Date.now() + delay > deadline) {
        return rejectWithValue({
          status,
          message: 'Still waiting for capacity. Please try again in a moment.',
        })
      }
      attempt += 1
      dispatch(waitingForCapacity())
      await sleep(delay)
    }
  }
})

const sessionSlice = createSlice({
  name: 'session',
  initialState,
  reducers: {
    sessionExpired(state) {
      state.status = 'idle'
      state.error = ''
      state.statusCode = null
      state.session = null
      state.expiresAt = null
    },
    resetSession(state) {
      state.status = 'idle'
      state.error = ''
      state.statusCode = null
      state.session = null
      state.expiresAt = null
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(waitingForCapacity, (state) => {
        state.status = 'waiting'
        state.error = ''
        state.statusCode = null
      })
      .addCase(createSession.pending, (state) => {
        state.status = 'loading'
        state.error = ''
        state.statusCode = null
      })
      .addCase(createSession.fulfilled, (state, action) => {
        state.status = 'success'
        state.session = action.payload
        state.statusCode = null
        state.expiresAt = Date.now() + action.payload.expiresInSeconds * 1000
      })
      .addCase(createSession.rejected, (state, action) => {
        const payload = action.payload as { status: number | null; message: string } | undefined
        state.status = 'error'
        state.statusCode = payload?.status ?? null
        state.error = payload?.message ?? action.error.message ?? 'Could not create stream session'
      })
  },
})

export const { sessionExpired, resetSession } = sessionSlice.actions
export default sessionSlice.reducer
