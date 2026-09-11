# Phase 1 - RTMP handshake/chunk-size/publish delay experiment

Status: not started
Scope: `java/rtmp` backend, `core/ClientSession.java` + tests

## Goal

Try the user's idea: when a node is near capacity, deliberately slow the RTMP
session handshake at a few stages (5s `handshake`, 3s `set chunk size`, etc.) to
pace the thundering-herd and buy a few seconds for the Phase-0 orchestration to
fire and a warm-pool node to promote.

## Honest expectation (read before implementing)

- RTMP `publish` is single-shot. The ack `NetStream.Publish.Start` is sent once
  (`ClientSession.java:547`); there is no protocol-level retry or backoff in OBS.
- A 5-10s delay CANNOT span the ~1 min warm-pool promotion. It can only smooth a
  node that is seconds away from room.
- A delayed ack does not free a slot: while the ack is pending the connection
  holds no slot, so the surplus still arrives and bounces if capacity is exhausted.
- Therefore this phase is an EXPERIMENT with a hard pass/fail gate. Expect FAIL;
  Phase 2 (HTTP-layer backoff) is the guaranteed path and is implemented regardless.

PASS = all 20 test publishers served without `BadName`/timeout within the throttle window.
FAIL = any rejected/timeout -> proceed with Phase 2 (docs/scaling/phase2-session-retry.md).

## Design

All throttling gated by config with default `0` = OFF (production-safe):

- `rtmp.throttle.min-streams` (default 18): only throttle when
  `server.activeStreamCount() >= threshold`. Effective off by default.
- `rtmp.throttle.handshake-ms` (default 0): sleep between `S0S1` and `S2`
  in `handleHandShake()` (`ClientSession.java:181-189`).
- `rtmp.throttle.chunk-size-ms` (default 0): sleep when processing the
  SetChunkSize control message (`ClientSession.java:235-239`).
- `rtmp.throttle.publish-ms` (default 0): sleep before the capacity check +
  `validatePublish` in the `publish` command handler
  (`ClientSession.java:508-515`), so the Phase-0 scale signal fires during the hold.

## Changes

### 1. `core/Server.java`

- Add `@Value("${rtmp.throttle.min-streams:18}")` (and the three ms values).
- Pass a small immutable `RtmpThrottleConfig` (or individual longs) into the
  `ClientSession` constructor (`Server.java:69`).
- Keep construction isomorphic to today (no new framework).

### 2. `core/ClientSession.java`

- Add fields for the throttle values; a `sleepMs(name, ms)` helper that logs at
  INFO when `ms > 0` and does `Thread.sleep` (virtual thread, so blocking is cheap).
- Guard each sleep behind the min-streams check:
  `if (throttle.enabled(server.activeStreamCount())) sleepMs(...)`.
- Insert:
  - handshake: after `sendS0S1(c0)`, before `sendS2(c1)`.
  - chunk-size: case 1 branch in `handleChunkMessage()`.
  - publish: before the `server.canAcceptStream()` check.

### 3. `application.properties`

- Add the four `rtmp.throttle.*` keys with `=0` defaults.
- CDK: optionally map `RTMP_THROTTLE_*` env vars (defaults 0) so the experiment
  can be tuned via task env without a rebuild. This is additive only.

### 4. Test harness

- Reuse the `rtmp-stress-test.yml` pattern: a script that starts N publish bots
  (ffmpeg) against 1 node at 14 active streams under the throttle.
- Record: number served, number `NetStream.Publish.BadName` / timeouts, total wall time.
- Script location suggestion: `java/rtmp/scripts/scale-throttle-test.ps1` + `.sh`
  (mirror the existing stress-test approach).

## Files touched

- Modified: `java/rtmp/src/main/java/com/nhochamvui/rtmp/core/Server.java`
- Modified: `java/rtmp/src/main/java/com/nhochamvui/rtmp/core/ClientSession.java`
- Modified: `java/rtmp/src/main/resources/application.properties`
- Modified: `java/rtmp/infra/aws/lib/cheap-ecs-ec2-stack.ts` (env passthrough, additive)
- Added: `java/rtmp/scripts/scale-throttle-test.{ps1,sh}`

## Verification

1. `./gradlew.bat test` (existing `RtmpSpec` still green; throttling off by default).
2. Run harness with `rtmp.throttle.handshake-ms=5000, chunk-size-ms=3000,
   publish-ms=2000, min-streams=14` against a node with 14 active streams and 20
   bots. Record PASS/FAIL per the gate.
3. Confirm no regression when all throttles are 0.

## Notes / gotchas

- Virtual threads make `Thread.sleep` safe here (no thread pool starvation), but
  cap total added latency (e.g. handshake+chunk+publish <= 10s) to stay under OBS
  default connect timeouts.
- Keep every sleep behind `rtmp.throttle.min-streams` so idle/normal operation is
  bit-for-bit identical to today.