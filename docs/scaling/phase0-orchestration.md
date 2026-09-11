# Phase 0 - Orchestration for fast scale-out (cheap stack)

Status: not started
Scope: `java/rtmp/infra/aws` (cheap mode) + `java/rtmp` backend `/health/ready`

## Goal

Replace the slow custom-metric target-tracking (`RTMP/ActiveStreams`, ~minutes)
with a proxy-polled, SQS-triggered, Lambda-driven scale-out path, and add an ASG
warm pool so replacement nodes boot in ~1 min instead of 2-4, plus a stream-capacity
signal so Traefik p2c steers new sessions away from near-full nodes.

## Why

Current scale-out waits for CloudWatch metric ingest + target-tracking (minutes).
A 20-streamer burst is rejected at `t=0` by the 18/node backstop
(`core/Server.java:97`, `core/ClientSession.java:511-515`) long before any policy
fires, and a cold EC2 boot takes 2-4 min regardless of trigger speed. We need the
signal to be near-real-time (seconds) and the replacement node to be pre-booted.

## Design

    node's rtmp_active_streams >= threshold (aggregate headroom < 18)
        ^ /prometheus scraped every 10s (proxy timer, 15s -> 10s)
    Proxy (t4g.nano, already enumerates node IPs)
        -- sqs:SendMessage (edge-triggered via /var/tmp latch) --> rtmp-scale-out queue
                                                                     | SQS event source (batchSize 1, maxConcurrency 1)
                                                                     v
                                                           scale-out Lambda (not in VPC)
                                                                     | ecs:DescribeServices / UpdateService +1
                                                                     v
                                                           rtmp-app-service desiredCount+1 (cap maxAppCount)
                                                                     | managed capacity provider
                                                                     v
                                                           ASG promotes a WARM instance (pre-booted, ECS-ready)
                                                                     | Traefik 10s re-render picks up new InService node
                                                                     v
                                                           new task placed (distinctInstances -> 1 task/instance)

### Trigger semantics

The proxy's existing `render-traefik-backends` loop already discovers live node
IPs every 15s (`cheap-ecs-ec2-stack.ts:264-290`). We extend that same 15s/10s loop:

- For each node IP: `curl -fsS http://<ip>:8888/prometheus`, parse `rtmp_active_streams`
  (Micrometer gauge, unauthenticated on the private network).
- Compute `freeSlots = SUM(18 - streams) over nodes`.
- When `freeSlots < 18` (headroom less than one node) AND it is an edge transition
  (previous sample was `>= 18`, tracked by a `/var/tmp` latch file) -> send one SQS message.
- Reset the latch when headroom recovers to `>= 18` (hysteresis prevents re-firing every 10s).

Note: `PrometheusFilter` can require `RTMP_PROMETHEUS_TOKEN`; on the private
network the scrape is unauthenticated. The filter already supports a token if we
later want to secure it.

## Why not Traefik / K8s

- Traefik is a proxy/LB only: no rules engine, no SQS, no AWS scaling API. It can
  503-reroute (that's the `/health/ready` part below) but cannot scale.
- K8s has the same EC2 boot wall (Cluster Autoscaler ~15s poll, then identical
  2-4 min boot) plus control-plane ops, and no warm-pool analog. For a
  singleton-per-micro-node RTMP workload, ECS EC2 + warm pool is strictly simpler.

## Changes

### 1. SQS queue + proxy IAM (`java/rtmp/infra/aws/lib/cheap-ecs-ec2-stack.ts`, infra stack)

- New `sqs.Queue` `rtmp-scale-out` (standard; low volume, free tier covers ~3 msgs/min).
  - `removalPolicy: DESTROY`, `visibilityTimeout` >= Lambda timeout (e.g. 60s for a 20s timeout).
- Grant `proxyRole` `sqs:SendMessage` on the queue.
- Add the queue URL + region to the proxy userData (interpolated at synth, so no
  re-fetch needed at runtime).

### 2. Proxy poller (proxy userData in the same file)

- Change the `traefik-backends.timer` `OnUnitActiveSec=15s -> 10s`
  (`cheap-ecs-ec2-stack.ts:307`).
- Append a `render-scale-signal` block at the end of `render-traefik-backends`
  (reuses its `PRIVATE_IPS` loop). Guarded with `|| echo 0` / defaults so a node
  mid-boot doesn't abort the Traefik render (`set -euo pipefail`).
  - Parsing: `awk '/^rtmp_active_streams/ {print int($2)}'` to drop the `.0` decimal.
  - Edge-trigger latch in `/var/tmp/scale-signal.state`.
  - `aws sqs send-message --queue-url <QURL> --message-body '{"freeSlots":N,...}'`.

### 3. Scale-out Lambda (new asset `java/rtmp/infra/aws/lambda/scale-out/index.mjs`)

- Node 20 runtime, `lambda.NodejsFunction` (requires `esbuild` devDep in
  `infra/aws/package.json`) or plain `lambda.Function` + `Code.fromAsset`.
- Handler: on each SQS message -> `ecs:DescribeServices rtmp-app-service` ->
  if `desiredCount < maxAppCount` -> `ecs:UpdateService desiredCount+1`.
- Env: `CLUSTER_NAME=rtmp-cheap`, `SERVICE_NAME=rtmp-app-service`,
  `MAX_APP_COUNT=<config.maxAppCount>`.
- `SqsEventSource` with `batchSize: 1`, `maxConcurrency: 1` (serialize probes = natural debounce).
- Role: `ecs:DescribeServices`, `ecs:UpdateService` scoped to
  `arn:aws:ecs:<region>:<acct>:service/rtmp-cheap/rtmp-app-service`.
- Not in a VPC (only calls the ECS control plane), so no NAT/extra IP cost.

### 4. ASG warm pool (same stack)

- `new autoscaling.WarmPool(scope, 'WarmPool', { autoScalingGroup, poolState: RUNNING, minSize: 1, maxGroupPreparedCapacity: 1 })`.
- Pre-boots one ECS-ready instance; promotion (`desiredCount +1`) takes ~1 min vs
  2-4 min cold. Warm instance is NOT InService, so Traefik's
  `describe-auto-scaling-groups ... InService` filter never routes to it.

### 5. Remove slow custom-metric scaling (same stack)

- Delete `scaling.scaleToTrackCustomMetric('StreamScaling', ...)`
  (`cheap-ecs-ec2-stack.ts:464-472`).
- Keep CPU 90% scaling as a CPU-driven backstop.
- Keep `RTMP_MAX_ACTIVE_STREAMS_PER_NODE=18` env and the 
  `StreamMetricsReporter` CW metric (still useful for dashboards).

### 6. App stream-capacity signal (`java/rtmp`)

- `StreamController.healthReady()` (`StreamController.java:85-103`): return 503
  also when `server.activeStreamCount() >= rtmp.health.max-streams` (new property,
  default 15). Include `activeStreams` in the body.
- Add `rtmp.health.max-streams=15` (or `${RTMP_HEALTH_MAX_STREAMS:15}`) to
  `application.properties`.
- CDK task env: add `RTMP_HEALTH_MAX_STREAMS: '15'`
  (`cheap-ecs-ec2-stack.ts:416-430`).
- Effect: Traefik p2c + `/health/ready` steers new HTTP session-create requests to
  the idle/node with room once a node reaches the soft cap of 15.

## Files touched

- Modified: `java/rtmp/infra/aws/lib/cheap-ecs-ec2-stack.ts`
- Modified: `java/rtmp/infra/aws/package.json` (+ esbuild if NodejsFunction)
- Added: `java/rtmp/infra/aws/lambda/scale-out/index.mjs`
- Added: `java/rtmp/infra/aws/lambda/scale-out/package.json`
- Modified: `java/rtmp/src/main/java/com/nhochamvui/rtmp/StreamController.java`
- Modified: `java/rtmp/src/main/resources/application.properties`
- Modified: `java/rtmp/infra/aws/README.md` (cheap-mode section)

## Verification

1. `npm run build` (tsc) then `npm run synth:cheap` in `infra/aws`.
2. Deploy; confirm queue `~rtmp-scale-out` has ApproximateNumberOfMessagesVisible ~0.
3. Flood publish bots; confirm:
   - SQS receives a message on low headroom (edge-triggered once).
   - Lambda bumps `desiredCount` by 1 (ECS console).
   - ASG promotes the warm instance in ~1 min.
   - `/health/ready` on a 15-stream node returns 503; a new session POST routes to the emptier node.
4. `./gradlew.bat test` in `java/rtmp` (controller/session specs still pass).

## Notes / gotchas

- `render-traefik-backends` uses `set -euo pipefail`; all curl/parse must be guarded.
- Warm pool RUNNING costs ~$3/mo per warm instance (that's the intended trade-off
  vs the old cold boot).
- Do NOT raise task `memoryReservationMiB` above 384 on t4g.nano (~418 MiB host budget).
- Timer cadence 10s matches Alloy's existing 10s scrape window.