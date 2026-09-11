# AWS RTMP Demo Infra

This CDK app deploys the Java RTMP demo in two cost profiles.

## Modes

- `cheap`: default. ECS on EC2 app instances behind a small Traefik proxy EC2 with an Elastic IP, plus Redis on the proxy, S3, and CloudFront. It avoids NAT Gateway, ALB, NLB, and ElastiCache.
- `managed`: ECS Fargate, local Redis sidecar by default, S3, CloudFront, and optional ALB. Run this only for short demos unless you accept the hourly cost.

## Prerequisites

Build and push the application image first:

```sh
docker build --platform linux/arm64 -t rtmp-demo ../../
aws ecr create-repository --repository-name rtmp-demo
docker tag rtmp-demo:latest <account>.dkr.ecr.<region>.amazonaws.com/rtmp-demo:latest
docker push <account>.dkr.ecr.<region>.amazonaws.com/rtmp-demo:latest
```

Cheap mode defaults to ARM64 app capacity on `t4g.micro`, so the app image should be built for `linux/arm64` unless you override the instance type. Use `-c instanceType=t3.micro` only with an amd64 image or a multi-arch image that includes amd64.

Create the HMAC secret:

```sh
aws ssm put-parameter \
  --name /rtmp/demo/hmac-secret \
  --type SecureString \
  --value '<at-least-32-random-bytes>'
```

Create the auth password and per-IP session limits:

```sh
aws ssm put-parameter \
  --name /rtmp/demo/auth-password \
  --type SecureString \
  --value '<ui-password>'

aws ssm put-parameter \
  --name /rtmp/demo/max-pending-per-ip \
  --type String \
  --value '10'

aws ssm put-parameter \
  --name /rtmp/demo/max-active-per-ip \
  --type String \
  --value '10'
```

The `max-pending-per-ip` and `max-active-per-ip` values map to the container env
vars `RTMP_MAX_PENDING_PER_IP` and `RTMP_MAX_ACTIVE_PER_IP`; change them in SSM
and redeploy (or restart tasks) to apply. ECS tasks fail to start if any of these
parameters is missing.

For GitHub Actions deployment, also configure:

- Repository secret `AWS_GITHUB_DEPLOY_ROLE_ARN`: IAM role ARN trusted by GitHub OIDC.
- CDK bootstrap in the target account/region: `npx cdk bootstrap aws://<account>/<region>`.

The workflow `.github/workflows/rtmp-aws-deploy.yml` builds the app image, pushes it to ECR repository `rtmp-demo`, then runs CDK `synth`, `deploy`, or `destroy` from manual dispatch inputs.

## Cheap Mode

Install and synthesize:

```sh
npm install
npm run synth:cheap -- -c appImage=<account>.dkr.ecr.<region>.amazonaws.com/rtmp-demo:latest
```

Deploy cheap mode with a stable proxy endpoint:

```sh
npx cdk deploy \
  -c deployMode=cheap \
  -c appImage=<account>.dkr.ecr.<region>.amazonaws.com/rtmp-demo:latest \
  -c desiredAppCount=2 \
  -c rtmpHost=rtmp.example.com
```

Cheap mode creates a small Traefik proxy EC2 instance with an Elastic IP. Point your domain `A` record to the `ProxyElasticIp` CloudFormation output. The API and RTMP publish URLs use the stable `rtmpHost` value, so EC2 replacement does not require a redeploy just to change URLs.

By default, cheap mode runs ARM64 ECS app instances on `t4g.micro` and expects the app image to include a `linux/arm64` manifest. To run x86 capacity instead, pass `-c instanceType=t3.micro` and build/push either an amd64 image or a multi-arch image.

The proxy instance is `t4g.nano` because it only runs Traefik, Redis for demo metadata, Docker, and a small backend refresh timer. Keep the ECS app capacity at `t4g.micro` or larger: the app task reserves 384 MiB (hard limit 640 MiB) for the Java RTMP container, and active streams start an `hls-segmenter` process (Go, ~20 MiB) outside the Java heap.

For a one-node demo:

```sh
npx cdk deploy \
  -c deployMode=cheap \
  -c appImage=<account>.dkr.ecr.<region>.amazonaws.com/rtmp-demo:latest \
  -c desiredAppCount=1 \
  -c rtmpHost=rtmp.example.com
```

For a short multi-node demo:

```sh
npx cdk deploy \
  -c deployMode=cheap \
  -c appImage=<account>.dkr.ecr.<region>.amazonaws.com/rtmp-demo:latest \
  -c desiredAppCount=3 \
  -c rtmpHost=rtmp.example.com
```

Cheap mode now maps app tasks to ECS EC2 instances with fixed ports:

- Public HTTP: Traefik proxy port `80`
- Public RTMP: Traefik proxy port `1935`
- App HTTP on each ECS EC2 instance: `8888`
- App RTMP on each ECS EC2 instance: `1935`
- Redis: container on the proxy EC2 private IP, port `6379`

`desiredAppCount` controls both the number of app tasks and the number of ECS EC2 instances. Each app task is placed on a distinct EC2 instance because all app tasks bind the same host ports. Use only one active stream per free-tier-size EC2 instance. `hls-segmenter` and S3 upload remain the real CPU/RAM floor.

HLS is mirrored to S3 by the `hls-segmenter` process itself (no separate uploader container): it PUTs each written segment/playlist asynchronously and prunes expired objects. There is no longer an aws-cli uploader sidecar.

### Scale-out (cheap mode)

Scale-out is driven by a near-real-time stream-capacity signal instead of the slow
`RTMP/ActiveStreams` CloudWatch target-tracking:

- The Traefik proxy polls each app node's `/prometheus` every 10s and sums free
  slots (`18 - rtmp_active_streams` per node).
- When headroom drops below one node's worth (edge-triggered via a `/var/tmp`
  latch), the proxy sends one message to the `rtmp-scale-out` SQS queue.
- A Lambda (outside any VPC) calls `ecs:UpdateService` to bump `desiredCount` by 1,
  capped at `maxAppCount`; the ECS capacity provider then promotes a pre-booted ASG
  warm-pool instance (~1 min instead of a 2-4 min cold boot).
- Each app node returns `503` from `/health/ready` once it reaches
  `RTMP_HEALTH_MAX_STREAMS` (default 15) active streams, so Traefik's p2c balancer
  steers new session creation toward an emptier node.

CPU target-tracking (90%) is kept as a CPU-driven backstop. The experimental RTMP
publish throttle (`RTMP_THROTTLE_HANDSHAKE_MS`, `RTMP_THROTTLE_CHUNK_SIZE_MS`,
`RTMP_THROTTLE_PUBLISH_MS`, gated by `RTMP_THROTTLE_MIN_STREAMS`) is off by default.

## Managed Mode

Managed mode is for short-lived cloud-native demos:

```sh
npx cdk deploy \
  -c deployMode=managed \
  -c appImage=<account>.dkr.ecr.<region>.amazonaws.com/rtmp-demo:latest \
  -c desiredAppCount=2 \
  -c enableElastiCache=true \
  -c enableAlb=true \
  -c enableNlb=true \
  -c rtmpHost=<rtmp-hostname>
```

For a single-task managed demo, leave `enableElastiCache=false` and the task uses a Redis sidecar. For `desiredAppCount > 1`, enable ElastiCache so all app tasks share stream-session and node state.

Destroy when finished:

```sh
npx cdk destroy -c deployMode=managed
```

## Cost Guardrails

- No NAT Gateway is created.
- Cheap mode does not create ALB, NLB, or ElastiCache.
- Cheap mode does create a proxy EC2 instance and an Elastic IP for stable DNS.
- CloudWatch log retention is three days.
- Buckets are destroyable by default for demo cleanup.
- Public IPv4, CloudFront, S3 requests, and running compute can still create charges.
