#!/usr/bin/env bash
#
# Baseline benchmark for hls-segmenter: per-stream process overhead (RSS, threads, CPU)
# in the current one-process-per-stream model. Run BEFORE and AFTER multiplexing.
#
# Only `hls-segmenter` processes are measured; the ffmpeg feeder (remux -c copy) is excluded.
#
# Usage: ./segmenter-bench.sh [STREAMS] [SECONDS] [RESOLUTION] [BITRATE]
#   e.g. ./segmenter-bench.sh 8 30 1280x720 2500k
set -uo pipefail

STREAMS="${1:-8}"
SECONDS_WIN="${2:-30}"
RES="${3:-1280x720}"
BR="${4:-2500k}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_ROOT="${OUT_ROOT:-$ROOT/build/bench}"
BIN="$OUT_ROOT/hls-segmenter"
SAMPLE="$OUT_ROOT/sample-$RES.flv"
SAMPLE_SECONDS=$((SECONDS_WIN + 10))

mkdir -p "$OUT_ROOT"

echo "[bench] building segmenter..."
( cd "$ROOT/go-hls" && go build -o "$BIN" . )

if [ ! -f "$SAMPLE" ]; then
  echo "[bench] generating $RES sample (${SAMPLE_SECONDS}s)..."
  ffmpeg -y -hide_banner -loglevel error \
    -f lavfi -i "testsrc=size=$RES:rate=30" \
    -f lavfi -i "sine=frequency=440" -t "$SAMPLE_SECONDS" \
    -c:v libx264 -preset ultrafast -tune zerolatency -b:v "$BR" \
    -c:a aac -b:a 64k -f flv "$SAMPLE"
fi

echo "[bench] launching $STREAMS stream(s), ${SECONDS_WIN}s window ..."
pids=()
for i in $(seq 1 "$STREAMS"); do
  dir="$OUT_ROOT/stream-$i"
  rm -rf "$dir"; mkdir -p "$dir"
  ( ffmpeg -hide_banner -loglevel error -re -i "$SAMPLE" -c copy -f flv - \
      | "$BIN" --out-dir "$dir" --hls-time 2 --hls-list-size 10 --hls-delete-threshold 1 \
  ) >/dev/null 2>&1 &
  pids+=($!)
done

sleep 3

first_cpu=""; last_cpu=""; t0=$(date +%s.%N)
declare -a rows
for _ in $(seq 1 "$SECONDS_WIN"); do
  read -r n rss thr cpu <<<"$(ps -C hls-segmenter -o rss=,nlwp=,times= 2>/dev/null | awk '{n+=1; rss+=$1; thr+=$2; cpu+=($3 ~ /:/ ? 1 : $3)} END {print n+0, rss+0, thr+0, cpu+0}')"
  rows+=("$n $rss $thr $cpu")
  [ -z "$first_cpu" ] && first_cpu=$cpu
  last_cpu=$cpu
  sleep 1
done
t1=$(date +%s.%N)

# cleanup
pkill -f 'hls-segmenter --out-dir' 2>/dev/null || true
for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done

n=$(printf '%s\n' "${rows[@]}" | awk '{if($1>m)m=$1} END{print m+0}')
avg_rss_kb=$(printf '%s\n' "${rows[@]}" | awk '{s+=$2; c++} END{printf "%.0f", (c?s/c:0)}')
avg_thr=$(printf '%s\n' "${rows[@]}" | awk '{s+=$3; c++} END{printf "%.0f", (c?s/c:0)}')
cores=$(awk -v a="$first_cpu" -v b="$last_cpu" -v t="$(awk -v x=$t0 -v y=$t1 'BEGIN{print y-x}')" 'BEGIN{ printf "%.2f", (t>0 ? (b-a)/t : 0) }')

echo
echo "==== hls-segmenter baseline (process-per-stream) ===="
echo "streams            : $STREAMS  (res $RES, $BR)"
echo "segmenter processes: $n"
echo "avg RSS total      : $(( avg_rss_kb / 1024 )) MB"
echo "avg RSS / process  : $(( avg_rss_kb / 1024 / (n>0?n:1) )) MB"
echo "avg threads total  : $avg_thr"
echo "avg threads / proc : $(( avg_thr / (n>0?n:1) ))"
echo "avg cores used     : $cores (this host: $(nproc) logical CPUs)"
echo "====================================================="

cat > "$OUT_ROOT/bench-results-$STREAMS-$RES.json" <<JSON
{"mode":"process-per-stream","streams":$STREAMS,"resolution":"$RES","bitrate":"$BR","segmenterProcs":$n,"avgRssTotalMB":$(( avg_rss_kb / 1024 )),"avgRssPerProcMB":$(( avg_rss_kb / 1024 / (n>0?n:1) )),"avgThreadsTotal":$avg_thr,"avgThreadsPerProc":$(( avg_thr / (n>0?n:1) )),"avgCoresUsed":$cores,"host":"$(hostname) $(nproc)cpu","timestamp":"$(date -Is)"}
JSON
echo "[bench] wrote $OUT_ROOT/bench-results-$STREAMS-$RES.json"
