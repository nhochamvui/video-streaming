import { createAsyncThunk, createSlice } from '@reduxjs/toolkit'
import { fetchHealth, fetchStats } from '../api/streams'
import type { StreamStats } from '../api/types'

interface StreamsState {
  namesStatus: 'idle' | 'loading' | 'success' | 'error'
  names: string[]
  thumbnails: Record<string, string>
  statsStatus: 'idle' | 'loading' | 'success' | 'error'
  stats: Record<string, StreamStats>
  lastUpdate: number
}

const initialState: StreamsState = {
  namesStatus: 'idle',
  names: [],
  thumbnails: {},
  statsStatus: 'idle',
  stats: {},
  lastUpdate: 0,
}

export const loadActiveStreams = createAsyncThunk('streams/loadNames', async () => {
  const health = await fetchHealth()
  return { names: health.streams ?? [], thumbnails: health.thumbnails ?? {} }
})

export const loadStats = createAsyncThunk('streams/loadStats', async () => {
  const stats = await fetchStats()
  return stats.activeStreams ?? {}
})

const streamsSlice = createSlice({
  name: 'streams',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(loadActiveStreams.pending, (state) => {
        state.namesStatus = 'loading'
      })
      .addCase(loadActiveStreams.fulfilled, (state, action) => {
        state.namesStatus = 'success'
        state.names = action.payload.names
        state.thumbnails = action.payload.thumbnails
      })
      .addCase(loadActiveStreams.rejected, (state) => {
        state.namesStatus = 'error'
      })
      .addCase(loadStats.pending, (state) => {
        state.statsStatus = 'loading'
      })
      .addCase(loadStats.fulfilled, (state, action) => {
        state.statsStatus = 'success'
        state.stats = action.payload
        state.lastUpdate = Date.now()
      })
      .addCase(loadStats.rejected, (state) => {
        state.statsStatus = 'error'
      })
  },
})

export default streamsSlice.reducer