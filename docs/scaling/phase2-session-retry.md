# Phase 2 - HTTP session-create backoff retry (guaranteed "buy time" path)

Status: not started
Scope: `java/rtmp` backend session API + `java/rtmp/frontend` session store/UI

## Goal

Make the capacity-limited session-creation path retryable and client-friendly so
a burst during scale-out shows a graceful wait instead of an error. This is the
GUARANTEED complement to Phase 1: the request flow is HTTP-first, RTMP-second, so
`POST /api/v1/stream-sessions` is the only admission point that is retryable and
p2c-steerable. A streamer who clicks "Create session" and sees
"Waiting for capacity..." gets a key once the Phase-0 warm-pool node lands
(~1 min) with no manual retry.

## When it matters

- Node pinned: `selectLeastLoadedNode()` returns a node and the session is pinned
  to it (`validatePublish` rejects `assignedServerId != serverId`,
  `LettuceStreamSessionRepository.java:37`).
- When all nodes are at `rtmp.limits.active-streams-per-node` (18),
  `StreamSessionService.create()` throws `StreamSessionLimitExceeded`
  (`StreamSessionService.java:52-54`), which the controller returns as 429
  (`StreamSessionController.java:32-33`).
- The 429 arrives ~seconds after the burst, BEFORE any node can have booted.
  Client auto-retry with backoff spans the gap.

## Design

    SessionController returns 503 + Retry-After when cluster capacity is exhausted
        (all nodes at the active-stream cap)  [429 stays for per-IP hard limits]
                 |
                 v
    Frontend auto-retries createStreamSession with exponential backoff + jitter
        (1s -> 2s -> 4s -> ... -> max ~2 min), distinct "waiting" status
                 |
                 v
    Warm-pool node lands (~1 min) -> retry succeeds -> stream key issued, seamless

## Changes

### 1. Backend: distinguish capacity-exhausted from per-IP limit

`java/rtmp/src/main/java/com/nhochamvui/rtmp/session/`:

- New exception type (e.g. `StreamCapacityUnavailable`) thrown by
  `StreamSessionService.create()` when the selected node(s) are ALL at
  `maxActiveStreamsPerNode` (i.e. `node.activeStreams() >= maxActiveStreamsPerNode`).
  Per-IP limits keep throwing `StreamSessionLimitExceeded`.
- `StreamSessionController.create()` catches the new type and returns
  `503 Service Unavailable` with body `{ error, retryAfterSeconds }`
  and header `Retry-After: <seconds>` (`StreamSessionController.java:32-36`).

### 2. Frontend: retry with backoff

`java/rtmp/frontend/src/`:

- `store/sessionSlice.ts`: add a retry loop in the `createSession` thunk for
  503/429 responses. Exponential backoff with jitter: wait = min(2^attempt * 1000,
  120000) + random(0..500). Stop after ~2 min and surface an error.
  - New state: `status: 'waiting'` (or reuse a counter) so the UI can show
    "Waiting for capacity..." during the retry window.
- `api/client.ts`: expose `Retry-After` header (optional) so the client respects
  the server hint when present.
- `components/session/CreateSessionCard.tsx`: when `status === 'waiting'`, show a
  non-blocking spinner + "Waiting for capacity, starting stream session
  automatically..." and disable the button. Keep the `statusCode === 401` ->
  sessionExpired + re-auth flow untouched.

### 3. Tests

- Spock: `StreamSessionServiceSpec` / a controller spec asserting:
  - all-nodes-at-cap returns 503 (not 429) with Retry-After;
  - per-IP limit still returns 429;
  - one node with a free slot still creates a session (no behaviour change).
- Frontend: (no test framework in repo for Redux slices today) verify via `tsc`
  + manual run against a dev backend with the throttles from Phase 1 on.

## Files touched

- Modified: `java/rtmp/src/main/java/com/nhochamvui/rtmp/session/StreamSessionService.java`
- Modified: `java/rtmp/src/main/java/com/nhochamvui/rtmp/session/StreamSessionController.java`
- Added: `java/rtmp/src/main/java/com/nhochamvui/rtmp/session/StreamCapacityUnavailable.java`
- Modified: `java/rtmp/src/test/groovy/...` (session/controller specs)
- Modified: `java/rtmp/frontend/src/store/sessionSlice.ts`
- Modified: `java/rtmp/frontend/src/api/client.ts`
- Modified: `java/rtmp/frontend/src/components/session/CreateSessionCard.tsx`

## Verification

1. `./gradlew.bat test` (new 503/429 specs green; existing specs green).
2. `npm run build` in `java/rtmp/frontend` (tsc --noEmit passes).
3. Manual: run backend with Phase-1 throttles + all nodes near cap -> click
   "Create stream session" -> observe "Waiting for capacity..." then a key appears
   when the warm node lands. No error surface.

## Notes / gotchas

- Keep the 429 for per-IP limits untouched (it is a genuine client error, not capacity).
- Cap total retry at ~2 min so UI doesn't spin forever if the cluster never frees up.
- Respect the server's `Retry-After` when sent; fall back to the exponential plan
  otherwise.