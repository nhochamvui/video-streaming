import { apiFetch } from './client'
import type { HealthResponse, StatsResponse, StreamStats } from './types'

export function fetchHealth(): Promise<HealthResponse> {
  return apiFetch<HealthResponse>('/stream-status')
}

export function fetchStats(): Promise<StatsResponse> {
  return apiFetch<StatsResponse>('/stats')
}

export function fetchStreamStats(playbackId: string): Promise<StreamStats> {
  return apiFetch<StreamStats>(`/stats/${encodeURIComponent(playbackId)}`)
}