# Architecture

High-level description of the project's structure and design.

## Overview

`video-streamming` is a self-hosted live video streaming platform. A streamer
pushes an RTMP stream from a broadcaster (e.g. OBS), the backend ingests it,
transcodes it to HLS, and serves it to viewers through a web player.

The system is built from three main parts:

- **Java backend** (`java/rtmp/`) — Micronaut HTTP API plus a custom RTMP
  ingestion server implemented from scratch.
- **Go HLS segmenter** (`java/rtmp/go-hls/`) — a native binary that converts
  the ingested FLV stream into fragmented MP4 HLS output and optionally mirrors
  it to S3.
- **React frontend** (`java/rtmp/frontend/`) — the streamer/watch UI built with
  React, Vite, Redux Toolkit and video.js, compiled into the backend's static
  resources.

Redis is the shared state store that lets multiple ingestion nodes coordinate:
session lease management, per-node heartbeats and least-loaded node selection.

## High-level architecture

```
                        ┌────────────────────────────────────────────┐
   Streamer (OBS)  ───▶ │  RTMP ingest :1935   (custom protocol)     │
                        │  ┌──────────────────────────────────────┐  │
                        │  │ Server ──▶ ClientSession (per conn)  │  │
                        │  │   · handshake / chunk parse / AMF0   │  │
                        │  │   · publish key validation           │  │
                        │  └──────────────┬───────────────────────┘  │
                        │                 │ FLV over stdin           │
                        │  ┌──────────────▼───────────────────────┐  │
                        │  │ hls-segmenter (Go)  ─▶  fMP4 HLS     │  │
                        │  │        hls/<playbackId>/hd/…         │  │
                        │  └───────┬──────────────────┬───────────┘  │
                        └──────────┼──────────────────┼─────────────┘
                                   │ local disk       │ S3 mirror
                                   ▼                  ▼
                        ┌──────────────────────┐  ┌──────────────────┐
                        │  Micronaut HTTP :8888 │  │  S3 + CloudFront │
                        │  · static UI         │  │  (HLS CDN)       │
                        │  · session API       │  └──────────────────┘
                        └──────────┬───────────┘
                                   │
                        ┌──────────▼───────────┐
                        │  Redis (coordination) │
                        │  · stream sessions    │
                        │  · node registry      │
                        └──────────────────────┘

   Viewer  ──▶ browser ──▶ video.js ──▶ HLS (local /hls or CloudFront)
```

Two separate listening ports:

- `1935` — RTMP ingestion (plain `ServerSocket`, one virtual thread per
  connection).
- `8888` — Micronaut HTTP: serves the built frontend, the JSON API, and the
  `/hls/**` static route for local playback during development.

## Repository layout

- `java/rtmp/` — Micronaut + Groovy backend, Go segmenter, frontend, Docker and
  AWS CDK infra.
  - `src/main/java/...` — backend source.
  - `src/test/groovy/...` — Spock tests.
  - `go-hls/` — Go segmenter (`hls-segmenter` binary).
  - `frontend/` — React/Vite UI.
  - `infra/aws/` — AWS CDK infrastructure.
  - `hls/` — local HLS output root (runtime).
- `lib/` — vendored Groovy 4.0.14 jars used by the build/tests.
- `docs/` — architecture and coding-standards documentation.
- `.github/workflows/` — CI/CD pipelines.

## Backend

The backend lives in `java/rtmp`. It is a Micronaut 4.8.2 application (Java 21)
using Netty for HTTP, Lettuce for Redis, GraalVM native-image support, and
Spock/Groovy for tests.

### Entry point

`Application.java` boots the Micronaut context and then grabs the `Server` bean
and calls `listen()`, blocking on the RTMP `ServerSocket`.

### HTTP API (`:8888`)

Controllers and filters:

- `StreamController` — serves the SPA (`/`, `/dashboard`, `/{playbackId}`),
  plus `GET /config` (HLS CDN URL), `GET /health`, `GET /stats` and
  `GET /stats/{playbackId}` (per-stream metrics), and `GET /version`.
- `session/StreamSessionController` — `POST /api/v1/stream-sessions` creates a
  publishing session and returns the ingest endpoint, stream key and playback
  URL.
- `auth/AuthController` — `POST /api/v1/auth/login`, `POST .../logout`,
  `GET .../me`, using an HTTP-only cookie session.
- `auth/AuthFilter` — protects `/api/v1/stream-sessions/**`; validates the
  session cookie.
- `RequestLoggingFilter` — request/response logging with an MDC `requestId`.

### RTMP ingestion (`:1935`)

The ingestion layer is a custom RTMP server (no third-party RTMP library):

- `core/Server` — accepts sockets and spawns a virtual thread per connection.
  Tracks active streams, rejects duplicate publishers, and heartbeats the node
  registry every 10s.
- `core/ClientSession` — per-connection state machine:
  - RTMP handshake (C0/C1/S0/S1/S2).
  - RTMP chunk stream parsing (fmt 0–3 headers, chunk-size negotiation).
  - AMF0 command handling (`connect`, `createStream`, `publish`,
    `deleteStream`, …).
  - Validates the stream key against the session service before authorizing
    `publish`.
  - Wraps incoming audio/video tags into an FLV stream and pipes it into the
    `hls-segmenter` subprocess (stdin).
  - Runs a stats reporter and a 60s session heartbeat that renews the Redis
    lease; if the lease is lost the connection is closed.
- `core/MediaHandler`, `core/AMF0Decoder`, and the model records
  (`RTMPHeader`, `Basic`, `Message`, `AMF0Message`, …) implement the
  FLV/AMF0 codec details.

### Session management (Redis)

Stream keys are short-lived, single-use credentials issued to streamers.

- `session/StreamSessionService` — orchestrates creation, validation,
  heartbeat and disconnect. Enforces per-IP limits
  (`rtmp.limits.pending-per-ip`, `rtmp.limits.active-per-ip`).
- `session/StreamSessionRepository` (interface) + `LettuceStreamSessionRepository`
  — persists sessions in Redis hashes keyed by an HMAC lookup key, using atomic
  Lua scripts for `validatePublish`, `heartbeat` and `disconnect` to prevent
  concurrent publishers from claiming the same session.
- `session/StreamKeyGenerator` — random 32-byte publish keys; `StreamKeyHasher`
  derives the lookup key and a 12-char fingerprint via HMAC-SHA256 so the raw
  key is never stored.
- `session/SafePlaybackPath` — maps a playback id to an HLS directory and
  guards against path traversal.

### Node registry (Redis)

Multiple ingestion nodes can share one Redis for coordination.

- `session/NodeRegistry` (interface) + `RedisNodeRegistry` — every node writes
  `server:<id>` (endpoint, status, active stream count, CPU load) with a 30s
  TTL. `selectLeastLoadedNode()` picks the active node with the fewest streams
  (CPU load as tiebreaker), falling back to self if none is registered.
- `session/ServerIdentity` — resolves the node's `server-id`
  (`RTMP_SERVER_ID`, or an auto-generated value).

### HLS egression (Go)

`ClientSession` launches one `hls-segmenter` process per live stream.

- `go-hls/main.go` — reads the FLV stream from stdin, drives the segmenter, and
  prints a periodic progress line (fps/bitrate/speed) that the Java host parses
  for its stats.
- `go-hls/segmenter.go` — demuxes FLV tags (H.264 AVC + AAC), produces
  fragmented MP4 (`init.mp4`, `output_N.m4s`, `output.m3u8`, `master.m3u8`) in
  `hls/<playbackId>/hd/`, and prunes segments outside the live window.
- `go-hls/uploader.go` — asynchronously mirrors segments/playlists to S3 on a
  single worker goroutine with a bounded queue, so S3 latency never blocks
  ingest. Playlists use `no-store` caching; on stream end the whole `hls/`
  prefix is deleted.

The master playlist references a single HD variant
(`BANDWIDTH=6000000,RESOLUTION=1920x1080`).

## Frontend

React 18 + TypeScript, bundled by Vite and served from the backend's static
resources (`base: '/static/'`).

- Pages: `HomePage` (create session + list streams), `PlayerPage` (watch a
  stream), `DashboardPage` (polling stats), `NotFoundPage`.
- State: Redux Toolkit slices `auth`, `session`, `streams`.
- Player: video.js with HLS. Resolves the playlist through `GET /config` — the
  CloudFront CDN URL when configured, otherwise the local `/hls` route.
- API layer: thin fetch wrappers (`client.ts`, `auth.ts`, `session.ts`,
  `streams.ts`).

During a Gradle build the frontend is built with `npm` and copied into
`src/main/resources/static`, so the runnable artifact contains the UI.

## Shared libraries

`lib/` (and `java/lib/`) vendor the Apache Groovy 4.0.14 jars required for the
Spock test runtime and Groovy tooling. There is no shared application code
module; shared behavior lives inside the `java/rtmp` project.

## Configuration

All settings are environment-variable driven (`application.properties`), with
safe local defaults:

- `MICRONAUT_SERVER_PORT` / `micronaut.server.port` — HTTP port (8888).
- `RTMP_PORT` — RTMP ingest port (1935).
- `REDIS_URI` — Redis connection (default `redis://localhost:6379`).
- `RTMP_SERVER_ID` — node identity for the registry.
- `RTMP_ENDPOINT` / `RTMP_PLAYBACK_BASE_URL` — advertised ingest/playback URLs.
- `RTMP_HLS_ROOT`, `RTMP_HLS_BUCKET`, `RTMP_HLS_REGION`, `RTMP_HLS_CDN_URL` —
  HLS output location, optional S3 mirror and CDN URL.
- `RTMP_HMAC_SECRET` — ≥32-byte secret used to hash stream keys.
- `RTMP_AUTH_USERNAME` / `RTMP_AUTH_PASSWORD` — UI/API login credentials.
- `RTMP_MAX_PENDING_PER_IP` / `RTMP_MAX_ACTIVE_PER_IP` — per-IP session limits.

## Deployment and infrastructure

### Container images

- `Dockerfile` — JVM image (Eclipse Temurin 21) with the shadow jar and the Go
  segmenter. Multi-stage: builds the Go binary, builds the frontend image, then
  the Gradle jar.
- `Dockerfile.native` — GraalVM native-image build producing a self-contained
  binary.
- `Dockerfile.native-runtime` — packages a prebuilt native binary with the Go
  segmenter and static assets.
- `docker-compose.yml` — local stack: `redis` + `rtmp-server` (built image).

### AWS CDK (`java/rtmp/infra/aws`)

Two deploy modes, both sharing `storage-stack.ts` (S3 bucket with 1-day
lifecycle + CloudFront distribution with optimized HLS caching: no-store
playlists, short-TTL segments):

- **Cheap mode** (`cheap-ecs-ec2-stack.ts`) — single-AZ ECS on EC2 with an
  auto-scaling capacity provider, a separate Traefik proxy EC2 instance that
  load-balances HTTP and RTMP to the app instances, and Redis running in Docker
  on the proxy. Exposes an Elastic IP for the advertised RTMP host.
- **Managed mode** (`managed-ecs-fargate-stack.ts`) — ECS Fargate service with
  optional ALB (HTTP), NLB (RTMP) and ElastiCache Redis; otherwise a sidecar
  Redis container. Secrets (HMAC secret, auth password) come from SSM Parameter
  Store.

### CI / CD

GitHub Actions workflows under `.github/workflows/`:

- `deploy.yml` — builds a GraalVM native image and pushes the frontend + app
  images to GHCR (tagged `latest`, version and commit SHA).
- `frontend-build.yml` — builds and pushes the frontend image to ECR.
- `rtmp-aws-deploy.yml` — runs CDK `synth`/`deploy`/`destroy` for either deploy
  mode, including ECS draining and task/service cleanup before destroy.
- `rtmp-stress-test.yml` — scheduled/manual load test that runs `ffmpeg` bots
  against the live server (matrix of publishers, optional 20-bot burst).

### Operations

`java/rtmp/manage.sh` wraps Docker ops on a host: install Docker, pull and run
the image with Watchtower auto-updates, tail logs, and a resource monitor.

## Streaming lifecycle

1. **Session creation** — the streamer logs in and POSTs
   `/api/v1/stream-sessions`. The service checks per-IP limits, selects the
   least-loaded ingest node from Redis, generates a publish key, and stores a
   PENDING session with a 300s TTL.
2. **Publishing** — OBS connects to `rtmp://<host>:1935/live/<key>`. The
   `ClientSession` validates the key against Redis (atomic Lua), registers the
   playback id with the local `Server`, and starts piping FLV into
   `hls-segmenter`.
3. **Egression** — the segmenter writes fMP4 HLS locally and mirrors it to S3
   (when configured). The session is renewed every 60s; when the stream ends,
   the disconnect script marks it and S3 objects are cleaned up.
4. **Playback** — a viewer opens `/{playbackId}`. The UI loads
   `/health`/`/stats` to confirm the stream is active and video.js plays
   `master.m3u8` from the local `/hls` route or the CloudFront CDN.

## Security considerations

- Stream keys are never stored in plaintext (HMAC-SHA256 lookup key +
  fingerprint); validation and lease handoff are atomic in Redis.
- Publishing is limited per source IP, and duplicate publishers are rejected.
- The playback path is sanitized against directory traversal.
- Auth sessions use a constant-time credential comparison and an HTTP-only,
  SameSite=Lax cookie; defaults are logged as unsafe and intended for local
  dev only.
- S3/CloudFront is private-origin only (OAI), the bucket is blocked from public
  access, and HLS objects expire automatically.

## Testing and verification

- Spock specs in `src/test/groovy/` cover the RTMP core (`RtmpSpec`), auth
  (`AuthControllerSpec`, `AuthSessionStoreSpec`) and session service
  (`StreamSessionServiceSpec`).
- Go segmenter tests in `go-hls/segmenter_test.go`.
- Runtime verification includes the `RTMP Stress Test` workflow and the
  `/health` + `/stats` endpoints.
- Lint/typecheck: the frontend runs `tsc --noEmit` during `npm run build`;
  Gradle exposes standard `test`/`check` tasks.