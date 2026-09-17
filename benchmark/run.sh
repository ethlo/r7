#!/usr/bin/env bash
#
# r7 gateway benchmark driver.
#
#   ./run.sh                          # everything, jvm-local, ~25 min
#   ./run.sh --quick                  # smoke test, ~3 min
#   ./run.sh --mode docker            # measure the shipped container
#   ./run.sh --scenario journal       # just the journaling cost matrix
#
# See README.md for the methodology and for what the numbers do and do not mean.

set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"

# ----------------------------------------------------------------- defaults

MODE="jvm-local"          # jvm-local | docker
SCENARIOS="baseline,passthrough,filtered,journal"
WORKLOADS="browser,headers,post"
TOOLS="wrk,wrk2"
THREADS=""                # default: min(nproc/2, 16), set below
CONNECTIONS=200
DURATION="30s"
WARMUP="20s"
RATE=20000                # wrk2 fixed request rate
JAR=""
OUT=""
GW_PORT=8888
GW_STATUS_PORT=18888
BACKEND_PORT=11111
KEEP_RUNNING=0
SKIP_PREFLIGHT=0

JVM_OPTS_DEFAULT="-XX:+UseZGC --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
JVM_OPTS="${JVM_OPTS:-$JVM_OPTS_DEFAULT}"

# ----------------------------------------------------------------- plumbing

c_red=$'\033[31m'; c_grn=$'\033[32m'; c_yel=$'\033[33m'; c_dim=$'\033[2m'; c_off=$'\033[0m'
log()  { printf '%s[bench]%s %s\n' "$c_dim" "$c_off" "$*" >&2; }
ok()   { printf '%s[bench]%s %s\n' "$c_grn" "$c_off" "$*" >&2; }
warn() { printf '%s[bench]%s %s\n' "$c_yel" "$c_off" "$*" >&2; }
die()  { printf '%s[bench]%s %s\n' "$c_red" "$c_off" "$*" >&2; exit 1; }

usage() {
  sed -n '3,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  cat <<'EOF'

Options:
  --mode jvm-local|docker    How to launch the gateway under test (default: jvm-local)
  --scenario LIST            baseline,passthrough,filtered,journal  (default: all)
  --workload LIST            browser,headers,post                   (default: all)
  --tool LIST                wrk,wrk2                               (default: both)
  --threads N                wrk threads       (default: min(nproc/2, 16))
  --connections N            open connections  (default: 200)
  --duration T               measured duration (default: 30s)
  --warmup T                 discarded warmup  (default: 20s; JIT needs it)
  --rate N                   wrk2 target req/s (default: 20000)
  --jar PATH                 gateway jar (default: newest r7-undertow/target/*.jar)
  --out DIR                  results dir (default: benchmark/results/<timestamp>)
  --quick                    5s warmup, 10s runs, browser workload only
  --keep-running             leave backend/gateway up after the run
  --skip-preflight           skip the host tuning checks
  -h, --help                 this
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode)        MODE="$2"; shift 2 ;;
    --scenario)    SCENARIOS="$2"; shift 2 ;;
    --workload)    WORKLOADS="$2"; shift 2 ;;
    --tool)        TOOLS="$2"; shift 2 ;;
    --threads)     THREADS="$2"; shift 2 ;;
    --connections) CONNECTIONS="$2"; shift 2 ;;
    --duration)    DURATION="$2"; shift 2 ;;
    --warmup)      WARMUP="$2"; shift 2 ;;
    --rate)        RATE="$2"; shift 2 ;;
    --jar)         JAR="$2"; shift 2 ;;
    --out)         OUT="$2"; shift 2 ;;
    --quick)       WARMUP="5s"; DURATION="10s"; WORKLOADS="browser"; shift ;;
    --keep-running) KEEP_RUNNING=1; shift ;;
    --skip-preflight) SKIP_PREFLIGHT=1; shift ;;
    -h|--help)     usage; exit 0 ;;
    *)             die "unknown option: $1 (try --help)" ;;
  esac
done

[[ "$MODE" == "jvm-local" || "$MODE" == "docker" ]] || die "--mode must be jvm-local or docker"

if [[ -z "$THREADS" ]]; then
  n="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 8)"
  THREADS=$(( n / 2 )); (( THREADS > 16 )) && THREADS=16; (( THREADS < 2 )) && THREADS=2
fi

TS="$(date +%Y%m%d-%H%M%S)"
OUT="${OUT:-$HERE/results/$TS}"
RUNDIR="$OUT/run"
RAW="$OUT/raw"
mkdir -p "$RUNDIR/config" "$RUNDIR/journals" "$RAW"

# ----------------------------------------------------------------- preflight

need() { command -v "$1" >/dev/null 2>&1 || die "missing required tool: $1"; }

preflight() {
  need docker
  need python3
  need curl
  [[ ",$TOOLS," == *",wrk,"*  ]] && need wrk
  [[ ",$TOOLS," == *",wrk2,"* ]] && need wrk2
  command -v wrk >/dev/null 2>&1 || command -v wrk2 >/dev/null 2>&1 \
    || die "need at least one of wrk / wrk2"

  if [[ "$MODE" == "jvm-local" ]]; then
    need java
    if [[ -z "$JAR" ]]; then
      JAR="$(ls -t "$REPO"/r7-undertow/target/r7-undertow-*.jar 2>/dev/null | grep -v sources | grep -v javadoc | head -n1 || true)"
    fi
    [[ -n "$JAR" && -f "$JAR" ]] || die "no gateway jar found; run 'mvn -q -DskipTests install' or pass --jar"
    log "jar: $JAR"
  fi

  (( SKIP_PREFLIGHT )) && return 0

  # These are the settings that silently cap a load test and make you think the
  # gateway is the bottleneck.
  local nofile; nofile="$(ulimit -n)"
  if [[ "$nofile" != "unlimited" ]] && (( nofile < CONNECTIONS * 4 )); then
    warn "ulimit -n is $nofile; with $CONNECTIONS connections you want >= $((CONNECTIONS * 4)). Try: ulimit -n 65535"
  fi

  if [[ -r /proc/sys/net/ipv4/ip_local_port_range ]]; then
    read -r lo hi < /proc/sys/net/ipv4/ip_local_port_range
    (( hi - lo < 20000 )) && warn "narrow ephemeral port range ($lo-$hi); consider: sysctl -w net.ipv4.ip_local_port_range='1024 65535'"
  fi
  if [[ -r /proc/sys/net/ipv4/tcp_tw_reuse ]] && [[ "$(cat /proc/sys/net/ipv4/tcp_tw_reuse)" == "0" ]]; then
    warn "net.ipv4.tcp_tw_reuse=0; TIME_WAIT sockets may throttle long runs"
  fi
  if [[ -r /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor ]]; then
    local gov; gov="$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)"
    [[ "$gov" == "performance" ]] || warn "cpufreq governor is '$gov', not 'performance'; expect run-to-run variance"
  fi

  warn "load generator and gateway share this host: they compete for CPU."
  warn "Numbers are valid for comparing runs against each other, not as absolute capacity."
}

# ----------------------------------------------------------------- lifecycle

BACKEND_CID=""
GW_PID=""
GW_COMPOSE=0

cleanup() {
  local rc=$?
  if (( KEEP_RUNNING )); then
    warn "--keep-running: leaving backend and gateway up"
    return $rc
  fi
  stop_gateway || true
  if [[ -n "$BACKEND_CID" ]]; then
    log "stopping backend"
    docker rm -f "$BACKEND_CID" >/dev/null 2>&1 || true
  fi
  return $rc
}
trap cleanup EXIT INT TERM

wait_for() {  # wait_for <url> <what> [tries]
  local url="$1" what="$2" tries="${3:-60}"
  for ((i = 0; i < tries; i++)); do
    if curl -fsS --max-time 1 -o /dev/null "$url" 2>/dev/null; then return 0; fi
    sleep 0.5
  done
  die "$what did not become ready at $url"
}

start_backend() {
  if ss -ltn 2>/dev/null | grep -q ":$BACKEND_PORT "; then
    die "port $BACKEND_PORT already in use; stop whatever is on it first"
  fi
  log "starting nginx reference backend on :$BACKEND_PORT"
  BACKEND_CID="$(docker run -d --rm \
    --name r7-bench-backend \
    --network host \
    --ulimit nofile=200000:200000 \
    -v "$HERE/backend/nginx.conf:/etc/nginx/nginx.conf:ro" \
    nginx:alpine)"
  wait_for "http://127.0.0.1:$BACKEND_PORT/__bench_health" "backend"
  ok "backend ready"
}

render_config() {  # render_config <journal-level>
  local level="$1"
  sed -e "s|__JOURNAL_LEVEL__|$level|g" \
      -e "s|__BACKEND_URL__|http://127.0.0.1:$BACKEND_PORT|g" \
      "$HERE/config/routes.yaml.tmpl" > "$RUNDIR/config/routes.yaml"

  local wd="$RUNDIR/journals"
  [[ "$MODE" == "docker" ]] && wd="/journals"
  sed -e "s|__WORK_DIR__|$wd|g" \
      "$HERE/config/server.yaml.tmpl" > "$RUNDIR/config/server.yaml"

  rm -rf "${RUNDIR:?}/journals"; mkdir -p "$RUNDIR/journals"
}

start_gateway() {  # start_gateway <journal-level>
  render_config "$1"
  if [[ "$MODE" == "jvm-local" ]]; then
    log "starting gateway (jvm-local, journal=$1)"
    ( cd "$RUNDIR" && exec java $JVM_OPTS ${R7_ARGS:-} -jar "$JAR" ) \
      > "$OUT/gateway-$1.log" 2>&1 &
    GW_PID=$!
  else
    log "starting gateway (docker, journal=$1)"
    GW_COMPOSE=1
    R7_BENCH_CONFIG="$RUNDIR/config" \
    R7_BENCH_JOURNALS="$RUNDIR/journals" \
    R7_BENCH_JVM_OPTS="$JVM_OPTS" \
    R7_BENCH_UID="$(id -u)" \
    R7_BENCH_GID="$(id -g)" \
      docker compose -f "$HERE/docker-compose.bench.yaml" up -d gateway
  fi
  wait_for "http://127.0.0.1:$GW_PORT/bench/__bench_health" "gateway" 120
  ok "gateway ready (journal=$1)"
}

stop_gateway() {
  if [[ -n "$GW_PID" ]]; then
    kill "$GW_PID" 2>/dev/null || true
    wait "$GW_PID" 2>/dev/null || true
    GW_PID=""
  fi
  if (( GW_COMPOSE )); then
    docker compose -f "$HERE/docker-compose.bench.yaml" down --remove-orphans >/dev/null 2>&1 || true
    GW_COMPOSE=0
  fi
  # give the port time to clear so the next scenario's bind does not race
  for _ in {1..20}; do
    ss -ltn 2>/dev/null | grep -q ":$GW_PORT " || break
    sleep 0.25
  done
}

# ----------------------------------------------------------------- workloads

workload_script() {
  case "$1" in
    browser) echo "$HERE/wrk/browser-get.lua" ;;
    headers) echo "$HERE/wrk/header-heavy.lua" ;;
    post)    echo "$HERE/wrk/post-json.lua" ;;
    *)       die "unknown workload: $1" ;;
  esac
}

# fire <tool> <scenario> <workload> <journal> <url>
fire() {
  local tool="$1" scenario="$2" workload="$3" journal="$4" url="$5"
  local script; script="$(workload_script "$workload")"
  local tag="${scenario}-${workload}-${tool}"
  [[ "$scenario" == "journal" ]] && tag="${scenario}-${journal}-${workload}-${tool}"

  # Warm up at full tilt regardless of which tool measures: C2 needs volume,
  # not a fixed rate. Prefer wrk; fall back to wrk2 if only that is installed.
  log "warmup  ${tag} (${WARMUP}, discarded)"
  if command -v wrk >/dev/null 2>&1; then
    wrk -t"$THREADS" -c"$CONNECTIONS" -d"$WARMUP" -s "$script" --timeout 5s "$url" \
      > "$RAW/$tag.warmup.txt" 2>&1 || true
  else
    wrk2 -t"$THREADS" -c"$CONNECTIONS" -d"$WARMUP" -R"$((RATE * 4))" -s "$script" \
      --timeout 5s "$url" > "$RAW/$tag.warmup.txt" 2>&1 || true
  fi
  sleep 2

  log "measure ${tag} (${DURATION})"
  if [[ "$tool" == "wrk" ]]; then
    wrk -t"$THREADS" -c"$CONNECTIONS" -d"$DURATION" --latency --timeout 5s \
        -s "$script" "$url" > "$RAW/$tag.txt" 2>&1 || true
  else
    wrk2 -t"$THREADS" -c"$CONNECTIONS" -d"$DURATION" -R"$RATE" --latency --timeout 5s \
         -s "$script" "$url" > "$RAW/$tag.txt" 2>&1 || true
  fi

  local meta
  meta="$(python3 - "$scenario" "$workload" "$tool" "$journal" "$MODE" "$RATE" <<'PY'
import json, sys
s, w, t, j, m, r = sys.argv[1:7]
print(json.dumps({"scenario": s, "workload": w, "tool": t, "journal": j,
                  "mode": m, "rate": int(r) if t == "wrk2" else None}))
PY
)"
  python3 "$HERE/lib/parse.py" parse "$RAW/$tag.txt" "$meta" > "$OUT/$tag.json"

  local rps; rps="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["stats"]["rps"] or 0)' "$OUT/$tag.json")"
  ok "  ${tag}: ${rps} req/s"
  sleep 3   # let sockets drain before the next run
}

has() { [[ ",$1," == *",$2,"* ]]; }

# ----------------------------------------------------------------- main

preflight
log "mode=$MODE threads=$THREADS conns=$CONNECTIONS duration=$DURATION warmup=$WARMUP rate=$RATE"
log "results -> $OUT"

{
  echo "mode=$MODE"
  echo "threads=$THREADS connections=$CONNECTIONS duration=$DURATION warmup=$WARMUP rate=$RATE"
  echo "host=$(uname -srm) cpus=$(getconf _NPROCESSORS_ONLN)"
  echo "java=$(java -version 2>&1 | head -n1 || echo n/a)"
  echo "wrk=$(wrk --version 2>&1 | head -n1 || echo n/a)"
  echo "wrk2=$(wrk2 --version 2>&1 | head -n1 || echo n/a)"
  echo "jar=$JAR"
  echo "git=$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo n/a)"
  echo "date=$(date -Is)"
} > "$OUT/environment.txt"

start_backend

IFS=',' read -ra WL <<< "$WORKLOADS"
IFS=',' read -ra TL <<< "$TOOLS"

# 1. Baseline: straight at the backend. Everything else is reported against this.
if has "$SCENARIOS" baseline; then
  log "=== scenario: baseline (no gateway) ==="
  for w in "${WL[@]}"; do for t in "${TL[@]}"; do
    fire "$t" baseline "$w" "-" "http://127.0.0.1:$BACKEND_PORT/bench/api/v1/users"
  done; done
fi

# 2. Pass-through: r7 in the path, no filters, journaling off.
if has "$SCENARIOS" passthrough; then
  log "=== scenario: passthrough (r7, no filters, journal=NONE) ==="
  start_gateway NONE
  for w in "${WL[@]}"; do for t in "${TL[@]}"; do
    fire "$t" passthrough "$w" NONE "http://127.0.0.1:$GW_PORT/bench/api/v1/users"
  done; done
  stop_gateway
fi

# 3. Filtered: same, through a representative filter chain.
if has "$SCENARIOS" filtered; then
  log "=== scenario: filtered (AddCorrelationId + header rewrites) ==="
  start_gateway NONE
  for w in "${WL[@]}"; do for t in "${TL[@]}"; do
    fire "$t" filtered "$w" NONE "http://127.0.0.1:$GW_PORT/filtered/api/v1/users"
  done; done
  stop_gateway
fi

# 4. Journal cost matrix: fresh JVM per level so nothing carries over.
if has "$SCENARIOS" journal; then
  log "=== scenario: journal (NONE -> METADATA -> HEADERS -> FULL) ==="
  for level in NONE METADATA HEADERS FULL; do
    start_gateway "$level"
    for w in "${WL[@]}"; do for t in "${TL[@]}"; do
      fire "$t" journal "$w" "$level" "http://127.0.0.1:$GW_PORT/bench/api/v1/users"
    done; done
    du -sh "$RUNDIR/journals" 2>/dev/null | awk -v l="$level" '{print "journal_bytes_" l "=" $1}' >> "$OUT/environment.txt"
    stop_gateway
  done
fi

# ----------------------------------------------------------------- report

python3 "$HERE/lib/parse.py" report "$OUT" --format md > "$OUT/REPORT.md"
python3 "$HERE/lib/parse.py" report "$OUT" --format tsv > "$OUT/summary.tsv"

echo
cat "$OUT/REPORT.md"
echo
ok "raw output:  $RAW"
ok "report:      $OUT/REPORT.md"
