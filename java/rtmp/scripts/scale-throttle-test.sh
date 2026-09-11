#!/usr/bin/env bash
#
# Phase 1 harness - RTMP handshake/chunk-size/publish throttle experiment.
# See docs/scaling/phase1-rtmp-throttle.md for the design and the PASS/FAIL gate.
#
# Pins one node near capacity with PRELOAD publishers, then bursts BOTS more and
# records how many are served vs rejected (NetStream.Publish.BadName) or timed
# out, plus the total wall time.
#
#   PASS = all BOTS served, none rejected/timed out, within WINDOW seconds.
#   FAIL = any rejected/timeout -> proceed with Phase 2
#          (docs/scaling/phase2-session-retry.md).
#
# Requires: curl, jq, ffmpeg.
# Start the backend with the experiment throttles enabled, e.g.:
#   RTMP_THROTTLE_MIN_STREAMS=14 \
#   RTMP_THROTTLE_HANDSHAKE_MS=5000 \
#   RTMP_THROTTLE_CHUNK_SIZE_MS=3000 \
#   RTMP_THROTTLE_PUBLISH_MS=2000 ./gradlew.bat run
#
# Overridable env: RTMP_HOST RTMP_PORT RTMP_API_PORT RTMP_USERNAME RTMP_PASSWORD
#                  PRELOAD BOTS RES BR DURATION WINDOW
set -uo pipefail

HOST="${RTMP_HOST:-127.0.0.1}"
RTMP_PORT="${RTMP_PORT:-1935}"
API_PORT="${RTMP_API_PORT:-8888}"
USERNAME="${RTMP_USERNAME:-admin}"
PASSWORD="${RTMP_PASSWORD:-changeme}"
PRELOAD="${PRELOAD:-14}"
BOTS="${BOTS:-20}"
RES="${RES:-640x360}"
BR="${BR:-800k}"
DURATION="${DURATION:-60}"
WINDOW="${WINDOW:-20}"

API_BASE="http://${HOST}:${API_PORT}"
WORKDIR="$(mktemp -d)"
COOKIES="${WORKDIR}/cookies.txt"

say() { printf '%s\n' "$*"; }

login() {
  local code
  code=$(curl -sS -o "${WORKDIR}/login.json" -w '%{http_code}' -c "$COOKIES" \
    -X POST "${API_BASE}/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}")
  if [ "$code" != "200" ]; then
    say "Login failed (HTTP ${code})"
    cat "${WORKDIR}/login.json"
    return 1
  fi
}

# create_session <outfile> -> echoes stream key
create_session() {
  local out=$1 code key
  code=$(curl -sS -o "$out" -w '%{http_code}' -b "$COOKIES" \
    -X POST "${API_BASE}/api/v1/stream-sessions")
  key=$(jq -r '.streamKey // empty' "$out" 2>/dev/null)
  if [ "$code" = "201" ] && [ -n "$key" ]; then
    printf '%s' "$key"
    return 0
  fi
  return 1
}

# publish <id> <logfile> [attempts]
publish() {
  local id=$1 logf=$2 attempts=${3:-1} a key
  for a in $(seq 1 "$attempts"); do
    key=$(create_session "${WORKDIR}/session-${id}.json") || { sleep 2; continue; }
    if ffmpeg -hide_banner -loglevel error \
        -re -f lavfi -i "testsrc=duration=${DURATION}:size=${RES}:rate=30" \
        -f lavfi -i "sine=frequency=440:duration=${DURATION}" \
        -c:v libx264 -preset ultrafast -tune zerolatency -b:v "$BR" \
        -c:a aac -b:a 64k -f flv \
        "rtmp://${HOST}:${RTMP_PORT}/live/${key}" >"$logf" 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

login || exit 2

say "Preloading ${PRELOAD} publishers on ${HOST}:${RTMP_PORT} ..."
prepids=()
for i in $(seq 1 "$PRELOAD"); do
  publish "pre${i}" "${WORKDIR}/pre-${i}.log" 20 &
  prepids+=($!)
done
sleep 3
ALIVE=0
for p in "${prepids[@]}"; do kill -0 "$p" 2>/dev/null && ALIVE=$((ALIVE + 1)); done
say "Preload active: ${ALIVE}/${PRELOAD}"

say "Bursting ${BOTS} publishers (window ${WINDOW}s) ..."
START=$(date +%s)
for i in $(seq 1 "$BOTS"); do
  publish "burst${i}" "${WORKDIR}/burst-${i}.log" 1 &
done
wait
END=$(date +%s)

SERVED=0; BADNAME=0; TIMEOUT=0
for i in $(seq 1 "$BOTS"); do
  f="${WORKDIR}/burst-${i}.log"
  if [ -s "$f" ] && grep -qi 'BadName' "$f"; then
    BADNAME=$((BADNAME + 1))
  elif [ -s "$f" ] && grep -qi 'timed out\|Connection refused\|not authorized\|error' "$f"; then
    TIMEOUT=$((TIMEOUT + 1))
  else
    SERVED=$((SERVED + 1))
  fi
done

WALL=$((END - START))
say "--------------------------------------------------"
say "PRELOAD=${PRELOAD} BOTS=${BOTS} RES=${RES} BR=${BR} DURATION=${DURATION}"
say "served=${SERVED} badname=${BADNAME} timeout=${TIMEOUT}"
say "total wall time=${WALL}s (window=${WINDOW}s)"

if [ "$SERVED" -eq "$BOTS" ] && [ "$BADNAME" -eq 0 ] && [ "$TIMEOUT" -eq 0 ]; then
  say "RESULT: PASS"
  exit 0
fi
say "RESULT: FAIL -> proceed with Phase 2 (docs/scaling/phase2-session-retry.md)"
exit 1
