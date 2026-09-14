# Segmenter multiplexing — one `hls-segmenter` process per node

Status: **implemented** (daemon + client) - measure before/after on the target node.
Branch: `feat/segmenter-multiplex`

## Goal

Replace "one `hls-segmenter` process per stream" with a **single long-lived daemon per
node** that serves all streams (one goroutine per stream) and shares **one** S3 uploader.
This raises streams-per-node by cutting per-stream process/runtime overhead. The HLS
output, playlist layout, S3/CloudFront layout, RTMP protocol and frontend are unchanged.

## Non-goals

- No change to the RTMP protocol, HLS output, playlists, or the frontend.
- No change to the AWS topology, auto-scaling plumbing, or the proxy.
- No Java in-process muxing (separate, larger effort).

## Current state (verified)

- `ClientSession.startFfmpeg()` (Java) spawns
  `hls-segmenter --out-dir <hlsBaseDir>/hd --hls-time 2 --hls-list-size 10 --hls-delete-threshold 1
  [--s3-bucket <b> --s3-region <r>]`, writes FLV to **stdin** (`createFlvHeader()` +
  `writeFlvTag(...)`), parses **stdout** for `fps=`/`bitrate=`/`speed=`, and in `cleanup()`
  closes stdin / `waitFor` / `destroyForcibly`.
- `go-hls/main.go` is single-stream: reads FLV from `os.Stdin`, one `Segmenter`, a
  per-process `s3Uploader`, a 1s ticker printing `ProgressLine()` to stdout, and
  `os.Exit(1)` on any error.
- `Segmenter` holds per-stream state and exposes `SetUploader(Uploader)`, `ProgressLine()`,
  `Finish()`. `s3Uploader` is a bounded queue + single worker behind the `Uploader` interface.

## Design

One daemon per node:

```
JVM                                     daemon (1 Go process)
 ├─ ClientSession A ─ connection ─┐
 ├─ ClientSession B ─ connection ─┤  ├─ goroutine per stream (Segmenter)
 ├─ ClientSession C ─ connection ─┘  ├─ 1 shared s3Uploader (bounded queue)
 └─ …×N                               └─ 1 Go runtime
```

- Listen on a local socket. Default **Unix domain socket** `/tmp/hls-segmenter.sock`
  (sidecar-friendly); TCP `127.0.0.1:9977` as an alternative (child-process friendly).
- **One connection = one stream.**

### Protocol (v1, line-framed)

```
C→S  {"outDir":"/app/hls/<playbackId>/hd","hlsTime":2,"listSize":10,"deleteThreshold":1}\n
C→S  <raw FLV bytes: FLV header + tags>            (exactly what the JVM writes today)
S→C  P <progress line>\n                           1/second (current ProgressLine())
S→C  E <message>\n                                 stream error → connection closes
S→C  D\n                                           stream finished/flushed
C     half-close write side → S runs seg.Finish() → sends D → closes
```

S3 bucket/region stay **process-global** (env/flags) — identical for every stream on a node.
Per-stream values (outDir + the three HLS params) come from the handshake.

## Changes

### Go (`java/rtmp/go-hls/`)

1. **`main.go`**
   - Add daemon mode: `hls-segmenter --listen unix:/tmp/hls-segmenter.sock [--s3-bucket …] [--s3-region …]`.
   - Create **one** `s3Uploader` at startup (when a bucket is set), `Start()` it, `defer Close(ctx)`,
     and share it across streams.
   - Keep the existing single-stream mode (`--out-dir …`) for tests / back-compat.
2. **New `daemon.go`**
   - `serve(listener)`: accept loop → one goroutine per connection.
   - `handleConn(conn)`: read the handshake line → `NewSegmenter(...)` → `SetUploader(shared)` →
     start a 1s ticker emitting `P <ProgressLine()>` → `streamTags(conn, seg)` →
     `seg.Finish()` → write `D`.
   - Errors send `E <msg>` and close **only that connection** — never `os.Exit`.
   - Unlink the socket path on start and on shutdown.
3. **Refactor** — extract `func streamTags(r io.Reader, seg *Segmenter) error` from the current
   `run()` (FLV header + tag loop), returning errors instead of exiting.
4. `segmenter.go` / `uploader.go` — interfaces unchanged.

### Java (`java/rtmp/src/main/java/com/nhochamvui/rtmp/core/`)

5. **New `SegmenterDaemon`** — start/supervise the binary **once**
   (`hls-segmenter --listen …`), restart on unexpected exit, expose readiness.
   Config: `rtmp.segmenter.command` (default `hls-segmenter`),
   `rtmp.segmenter.listen` (default `unix:/tmp/hls-segmenter.sock`).
6. **New `SegmenterClient`** — connect → handshake → `write(byte[])` → read `P/E/D`;
   surface progress fields / error / done; `close()` = half-close + await `D`.
7. **`ClientSession`**
   - `startFfmpeg()` → `startSegmenter()` using `SegmenterClient` (same FLV bytes, same stats parsing).
   - `cleanup()` → closes the client (no `destroyForcibly`).
   - **Safe fallback**: `rtmp.segmenter.mode=daemon|process` (default `daemon`); if the socket is
     unavailable, fall back to the current per-process spawn.
8. **Tests** — `SegmenterClient` protocol test; `daemon_test.go` (two concurrent streams over an
   in-memory/Unix listener → two valid `init.mp4`/`output_N.m4s`/`output.m3u8` trees).

### Deployment

- **No change required**: the binary is already in the app image; the app spawns the daemon at start.
- Later (optional): run it as a **sidecar container** in the same ECS task.

## Verification plan

1. `cd java/rtmp/go-hls && go test ./...` — including the new daemon test.
2. `cd java/rtmp && ./gradlew test` — existing 35 + new protocol tests.
3. End-to-end locally: run the app, publish 2–3 streams, confirm playback works and
   `pgrep -c hls-segmenter` == 1.
4. Measure before/after on the same instance type: process count, RSS, streams-per-node at ~95% CPU.
   Re-tune `RTMP_MAX_ACTIVE_STREAMS_PER_NODE` from the result.

## Baseline measurement (before)

A harness ships on this branch to quantify the current per-process overhead:
`java/rtmp/scripts/segmenter-bench.ps1` (Windows) and `segmenter-bench.sh` (Linux/EC2).
It feeds N concurrent streams into N `hls-segmenter` processes and samples **only** the
segmenter processes (RSS, threads, CPU); results land in `build/bench/bench-results-*.json`.

Indicative run (Windows dev box, 1280x720 @ 2500k, realtime-paced):

| streams | procs | avg RSS / proc | total RSS | threads / proc | total threads | cores used |
|---|---|---|---|---|---|---|
| 4 | 4 | ~32 MB | ~128 MB | ~9.5 | 38 | ~0.01 |
| 8 | 8 | ~30 MB | ~242 MB | ~9 | 72 | ~0.02 |

Findings:

- Overhead is **~30 MB RSS and ~9 threads per stream**, growing linearly with stream count.
- At the current cap of 18 streams that is **~550 MB of segmenter RSS** — which already
exceeds the app task's 384 MiB memory limit. So the per-node budget looks **memory-bound,
not CPU-bound** (consistent with the earlier "bound streams per node / prevent OOM" commits),
and this is exactly what the multiplex removes.
- Segmenter CPU under realtime pacing is tiny (~0.02 cores for 8 streams): the heavy CPU sits
  in the encoder on the streamer side and in the JVM. So the expected multiplex win is
  **memory + threads first, CPU second**.

Caveat: indicative numbers from a Windows dev box. Run `segmenter-bench.sh` on the target EC2
node (and again after the change) for the authoritative before/after comparison.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Daemon crash drops all streams on the node | Supervisor restarts it; affected `ClientSession`s close and their Redis leases expire (reconnect is possible) |
| Backpressure on the socket blocks the RTMP reader | Same as today's pipe; keep bounded buffers and document |
| Stale socket file after crash | Unlink on start and shutdown |
| Regression | `rtmp.segmenter.mode=process` restores today's behaviour with no Go redeploy |

## Out of scope (later)

- Java in-process muxing.
- Sidecar containerization.
- Protocol v2: send parsed samples instead of re-serialized FLV.

## Rollback

Set `rtmp.segmenter.mode=process` (per-stream spawn), or revert the branch. No data migration.
