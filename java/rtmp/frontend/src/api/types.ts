export interface StreamSession {
  serverUrl: string
  streamKey: string
  playbackUrl: string
  expiresInSeconds: number
}

export interface HealthResponse {
  status: 'idle' | 'streaming'
  activeStreams: number
  streams: string[]
  thumbnails?: Record<string, string>
}

export interface StreamStats {
  uptimeSec: number
  delayMs: number
  droppedPackets: number
  keyframeCount: number
  maxKeyframeIntervalMs: number
  audioPackets: number
  audioBytesHuman: string
  videoPackets: number
  videoBytesHuman: string
  bitrateHuman: string
  ffmpegFps: number
  ffmpegSpeed: number
}

export interface StatsResponse {
  activeStreams: Record<string, StreamStats>
}

export interface StreamStatsError {
  error: string
  playbackId: string
}