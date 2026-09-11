# video-streamming

A self-hosted live video streaming platform. A streamer pushes an RTMP stream (for
example from OBS), the backend ingests it and transcodes it to HLS, and viewers watch
through a web player. The repo ships a Java/Micronaut backend with a from-scratch RTMP
server, a Go HLS segmenter, a React player UI, and AWS infrastructure defined with the
AWS CDK (including a low-cost "cheap" deployment profile).

## Architecture

### Components

| Component | Location | What it does |
|---|---|---|
| Java backend | `java/rtmp` | Micronaut 4.8.2 / Java 21. Custom RTMP ingestion (`:1935`), HTTP API + SPA (`:8888`), Redis-backed stream sessions and node registry. |
| Go HLS segmenter | `java/rtmp/go-hls` | `hls-segmenter` binary: FLV -> fragmented MP4 HLS, live-window pruning, optional async S3 mirror, thumbnail capture. |
| React frontend | `java/rtmp/frontend` | Vite + Redux Toolkit + video.js. Compiled into the backend's static resources. |
| Infrastructure | `java/rtmp/infra/aws` | AWS CDK (TypeScript). Two profiles: `cheap` (ECS on EC2) and `managed` (Fargate). |

### AWS cheap mode

The `cheap` profile avoids NAT Gateway, ALB, NLB and ElastiCache, and runs in a single
AZ. Public traffic lands on a tiny Traefik proxy instance; application containers run on
ECS-over-EC2 capacity that can scale out.

```
                  Elastic IP
                      |
   OBS --rtmp:1935--> [ Traefik proxy EC2 (t4g.nano) ] --http:80--> (viewer)
                      |   - Traefik (LB / p2c)
                      |   - Redis (docker, :6379)
                      |   - Grafana Alloy (metrics scrape)
                      |
                      +--> ECS cluster "rtmp-cheap" (EC2 / ASG, t4g.micro app nodes)
                             app task (host net): :8888 HTTP, :1935 RTMP
                             + hls-segmenter (FLV -> fMP4 HLS, local hls/ + S3 mirror)
                                     |
                                     +--> S3 bucket --> CloudFront (private origin / OAI) --> viewer HLS
```

Ingest / playback path:

```
publish:  OBS -> rtmp://<rtmpHost>:1935/live/<key> -> Traefik -> app node -> hls-segmenter -> hls/ + S3
playback: viewer -> http://<rtmpHost>/<playbackId> -> Traefik -> app node (SPA/API)
          video.js -> CloudFront master.m3u8 (or local /hls in development)
```

Fast scale-out (no slow custom-metric target tracking):

```
proxy polls each node /prometheus every 10s -> freeSlots = SUM(18 - rtmp_active_streams)
   headroom < one node (edge-triggered via a /var/tmp latch) -> SQS "rtmp-scale-out"
   -> Lambda -> ecs:UpdateService desiredCount + 1 (capped at maxAppCount)
   -> ECS capacity provider promotes a pre-booted ASG warm-pool instance (~1 min)
   -> Traefik 10s re-render routes new traffic to the node
```

Graceful admission during scale-out: when every node is at capacity,
`POST /api/v1/stream-sessions` returns `503` with `Retry-After`, and the UI retries with
backoff (up to ~5 min) showing "Waiting for capacity..." instead of failing. Per-IP
limits still return `429`.

Main AWS resources (cheap mode): VPC (single AZ, public subnets), Traefik proxy EC2 +
Elastic IP, ECS cluster + EC2 Auto Scaling group with an ASG warm pool, SQS
`rtmp-scale-out` queue + scale-out Lambda, S3 bucket (1-day lifecycle) + CloudFront
distribution, SSM parameters for secrets/limits. See
[`java/rtmp/infra/aws/README.md`](java/rtmp/infra/aws/README.md) for deployment.

## Prerequisites

- **JDK 21** (Temurin or GraalVM) for the backend.
- **Node.js 22** for the frontend and the CDK app.
- **Go 1.25** to build the HLS segmenter.
- **Docker + Docker Compose** for the local stack.
- **AWS CLI + CDK bootstrap** only for deployment.

## Run locally

Login for both options is `admin` / `$RTMP_AUTH_PASSWORD` (default `admin`). After
logging in, click **Create stream session** to get a server URL and a stream key.

### Option A - backend in IntelliJ + Docker Compose for the dependencies

Best for day-to-day backend work: Redis and Traefik run in Docker, the JVM runs on the
host so you get fast hot-reload and debugger support.

```sh
# 1. Start Redis + Traefik
cd java/rtmp
docker compose up -d            # redis :6379, traefik :80

# 2. Build the Go segmenter once and put it on PATH
cd go-hls
go build -o hls-segmenter .
#    Windows: add java/rtmp/go-hls to PATH (a prebuilt hls-segmenter.exe is committed);
#    the backend spawns the command "hls-segmenter" when a stream starts.
```

3. Run the app from **IntelliJ**: create a run configuration with main class
   `com.nhochamvui.rtmp.Application` (or run `./gradlew run` from `java/rtmp`).
4. Set env vars if you want (IDE run configuration, or `java/rtmp/.env`):
   `RTMP_HMAC_SECRET` (>= 32 random chars), `RTMP_AUTH_USERNAME`, `RTMP_AUTH_PASSWORD`.
5. Open the UI at <http://localhost> (Traefik routes to the host app on `:8888`), and
   publish with OBS to `rtmp://localhost:1935/live/<stream-key>`.

Notes: the base compose file publishes only Traefik `:80`, so RTMP goes directly to the
host app on `:1935`. Redis is reachable at `localhost:6379`, which matches the backend's
default `REDIS_URI`.

### Option B - everything in Docker Compose

One command runs Redis, Traefik and the application container (built from the
`Dockerfile`, which also compiles the Go segmenter and the frontend).

```sh
cd java/rtmp

# optional but recommended: create .env next to the compose files
#   RTMP_HMAC_SECRET=<at least 32 random characters>
#   RTMP_AUTH_PASSWORD=<your password>

docker compose -f docker-compose.yml -f docker-compose.docker.yml up --build
```

Open <http://localhost> and publish to `rtmp://localhost:1935/live/<stream-key>`
(Traefik forwards to the `rtmp-server` container). Stop with `Ctrl-C`, remove with
`docker compose -f docker-compose.yml -f docker-compose.docker.yml down`.

### Publishing a test stream (OBS or ffmpeg)

- **Server**: `rtmp://localhost:1935/live` (local) or `rtmp://<rtmpHost>:1935/live` (AWS)
- **Stream key**: the key from the UI
- **Watch**: `http://localhost/<playbackId>`

```sh
ffmpeg -re \
  -f lavfi -i "testsrc=size=1280x720:rate=30" \
  -f lavfi -i "sine=frequency=440" \
  -c:v libx264 -preset ultrafast -tune zerolatency -b:v 2500k \
  -c:a aac -b:a 64k -f flv \
  "rtmp://localhost:1935/live/<stream-key>"
```

## Deploy to AWS (cheap mode)

```sh
cd java/rtmp/infra/aws
npm install
npx cdk deploy \
  -c deployMode=cheap \
  -c appImage=<account>.dkr.ecr.<region>.amazonaws.com/rtmp-demo:latest \
  -c desiredAppCount=1 \
  -c maxAppCount=3 \
  -c rtmpHost=<your-hostname>
```

Point your domain's `A` record at the `ProxyElasticIp` stack output. Create the SSM
parameters (`/rtmp/demo/hmac-secret`, `/rtmp/demo/auth-password`,
`/rtmp/demo/max-pending-per-ip`, `/rtmp/demo/max-active-per-ip`) before deploying -
tasks fail to start if they are missing. Full details, including GitHub Actions
deployment, are in [`java/rtmp/infra/aws/README.md`](java/rtmp/infra/aws/README.md).

## Repository layout

```
java/rtmp/                Backend, segmenter, frontend and infra
  src/main/java/...       Micronaut backend (RTMP core, session API, health)
  src/test/groovy/...     Spock specs
  go-hls/                 Go HLS segmenter (hls-segmenter)
  frontend/               React/Vite UI (compiled into static resources)
  infra/aws/              AWS CDK app (cheap + managed profiles)
  docker-compose.yml      Local deps for IntelliJ (redis + traefik)
  docker-compose.docker.yml  Full local stack override (+ rtmp-server)
  traefik/                Local Traefik config (static + dynamic)
  hls/                    Local HLS output (runtime, git-ignored)
lib/                      Vendored Groovy jars for the build/tests
docs/                     architecture + scaling phase plans
.github/workflows/        CI/CD (ci.yml, deploy, stress test)
```

## CI and code quality

- `.github/workflows/ci.yml` - on every push and pull request: backend tests (Gradle,
  which also builds the frontend) and infra typecheck + `cdk synth`.
- `.github/workflows/codeql.yml` - CodeQL analysis for Java and JavaScript/TypeScript.
- `.github/dependabot.yml` - weekly dependency updates (Gradle, npm, Go, Actions, Docker).
- `.github/CODEOWNERS` - default reviewers.

## Documentation

- [`java/rtmp/infra/aws/README.md`](java/rtmp/infra/aws/README.md) - AWS deployment guide.

## License

See [LICENSE](LICENSE).
