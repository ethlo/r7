#!/usr/bin/env bash
#
# r7 gateway benchmark driver.
#
#   ./run.sh                          # default suite, jvm-local
#   ./run.sh --quick                  # smoke test, ~3 min
#   ./run.sh --repeat 3               # median of 3, with a visible noise floor
#   ./run.sh --scenario sweep         # rate sweep: find the latency knee
#   ./run.sh --mode docker            # measure the shipped container
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
RATE=20000                # wrk2 fixed request rate for the main suite
REPEAT=1
RESTART_PER_REPEAT=0
SWEEP_RATES="20000,40000,80000,120000,160000"
SWEEP_LEVELS="NONE,FULL"
JOURNAL_LEVELS="METADATA,HEADERS,FULL"   # NONE is covered by `passthrough`
JAR=""
OUT=""
GW_PORT=8888
GW_STATUS_PORT=18888
BACKEND_PORT=11111
KEEP_RUNNING=0
SKIP_PREFLIGHT=0
BACKEND_CPUS=""           # CPU lists (taskset syntax, e.g. 2-3) to pin each process to;
GATEWAY_CPUS=""           # empty means unpinned. bench.sh derives them from the host's
LOAD_CPUS=""              # core layout.
PIN_SLICE="r7bench.slice"

# Pinned by digest (nginx 1.31.6) so the backend cannot change under a comparison.
BACKEND_IMAGE="${R7_BENCH_BACKEND_IMAGE:-nginx:alpine@sha256:df221db836e1754089190208cee7eeda94f233197056426eda74a43ab1abeac2}"

JVM_OPTS_DEFAULT="-XX:+UseZGC --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
# Set but empty means no extra options: in docker mode, the image's own entrypoint flags.
JVM_OPTS="${JVM_OPTS-$JVM_OPTS_DEFAULT}"

# ----------------------------------------------------------------- plumbing

c_red=$'\033[31m'; c_grn=$'\033[32m'; c_yel=$'\033[33m'; c_dim=$'\033[2m'; c_off=$'\033[0m'
log()  { printf '%s[bench]%s %s\n' "$c_dim" "$c_off" "$*" >&2; }
ok()   { printf '%s[bench]%s %s\n' "$c_grn" "$c_off" "$*" >&2; }
warn() { printf '%s[bench]%s %s\n' "$c_yel" "$c_off" "$*" >&2; }
die()  { printf '%s[bench]%s %s\n' "$c_red" "$c_off" "$*" >&2; exit 1; }

usage() {
  sed -n '3,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  cat <<'EOF'

Options:
  --mode jvm-local|docker    How to launch the gateway under test (default: jvm-local)
  --scenario LIST            baseline,passthrough,filtered,journal,sweep
                             (default: all but sweep)
  --workload LIST            browser,headers,post                   (default: all)
  --tool LIST                wrk,wrk2                               (default: both)
  --threads N                wrk threads       (default: min(nproc/2, 16))
  --connections N            open connections  (default: 200)
  --duration T               measured duration (default: 30s)
  --warmup T                 discarded warmup  (default: 20s; JIT needs it)
  --rate N                   wrk2 target req/s for the main suite (default: 20000)
  --repeat N                 repeats per configuration; report shows median and
                             peak-to-peak spread (default: 1, use >= 3)
  --restart-per-repeat       fresh JVM for every repeat: also captures JIT and
                             JVM-to-JVM variance, at the cost of a warmup each
  --sweep-rates LIST         rates for the sweep scenario
                             (default: 20000,40000,80000,120000,160000)
  --sweep-levels LIST        journal levels to sweep (default: NONE,FULL)
  --journal-levels LIST      levels for the journal scenario
                             (default: METADATA,HEADERS,FULL)
  --jar PATH                 gateway jar (default: newest r7-helidon/target/*.jar)
  --out DIR                  results dir (default: benchmark/results/<timestamp>)
  --quick                    5s warmup, 10s runs, browser workload only
  --backend-cpus LIST        pin nginx to these CPUs (e.g. 1)
  --gateway-cpus LIST        pin the gateway to these CPUs (e.g. 2-3)
  --load-cpus LIST           pin wrk/wrk2 to these CPUs (e.g. 4-5); --threads
                             then defaults to the number of CPUs listed
  --keep-running             leave backend/gateway up after the run
  --skip-preflight           skip the host tuning checks
  -h, --help                 this

Notes:
  `passthrough` (r7 in the path, no filters, journal=NONE) is the denominator for
  filter and journal cost, so the journal scenario no longer re-measures NONE and
  selecting it implies passthrough. The report's `vs r7` column uses it.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode)          MODE="$2"; shift 2 ;;
    --scenario)      SCENARIOS="$2"; shift 2 ;;
    --workload)      WORKLOADS="$2"; shift 2 ;;
    --tool)          TOOLS="$2"; shift 2 ;;
    --threads)       THREADS="$2"; shift 2 ;;
    --connections)   CONNECTIONS="$2"; shift 2 ;;
    --duration)      DURATION="$2"; shift 2 ;;
    --warmup)        WARMUP="$2"; shift 2 ;;
    --rate)          RATE="$2"; shift 2 ;;
    --repeat)        REPEAT="$2"; shift 2 ;;
    --restart-per-repeat) RESTART_PER_REPEAT=1; shift ;;
    --sweep-rates)   SWEEP_RATES="$2"; shift 2 ;;
    --sweep-levels)  SWEEP_LEVELS="$2"; shift 2 ;;
    --journal-levels) JOURNAL_LEVELS="$2"; shift 2 ;;
    --jar)           JAR="$2"; shift 2 ;;
    --out)           OUT="$2"; shift 2 ;;
    --quick)         WARMUP="5s"; DURATION="10s"; WORKLOADS="browser"; shift ;;
    --backend-cpus)  BACKEND_CPUS="$2"; shift 2 ;;
    --gateway-cpus)  GATEWAY_CPUS="$2"; shift 2 ;;
    --load-cpus)     LOAD_CPUS="$2"; shift 2 ;;
    --keep-running)  KEEP_RUNNING=1; shift ;;
    --skip-preflight) SKIP_PREFLIGHT=1; shift ;;
    -h|--help)       usage; exit 0 ;;
    *)               die "unknown option: $1 (try --help)" ;;
  esac
done

[[ "$MODE" == "jvm-local" || "$MODE" == "docker" ]] || die "--mode must be jvm-local or docker"
[[ "$REPEAT" =~ ^[0-9]+$ ]] && (( REPEAT >= 1 )) || die "--repeat must be a positive integer"

has() { [[ ",$1," == *",$2,"* ]]; }

# The journal scenario reports against passthrough, so it needs it measured.
if has "$SCENARIOS" journal && ! has "$SCENARIOS" passthrough; then
  warn "journal scenario needs passthrough as its NONE reference; adding it"
  SCENARIOS="passthrough,$SCENARIOS"
fi


# Number of CPUs in a list like "2-3,6".
cpu_count() {
  local n=0 part lo hi
  IFS=',' read -ra parts <<< "$1"
  for part in "${parts[@]}"; do
    lo="${part%-*}"; hi="${part#*-}"
    n=$(( n + hi - lo + 1 ))
  done
  echo "$n"
}

if [[ -z "$THREADS" && -n "$LOAD_CPUS" ]]; then
  THREADS="$(cpu_count "$LOAD_CPUS")"
fi
if [[ -z "$THREADS" ]]; then
  n="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 8)"
  THREADS=$(( n / 2 )); (( THREADS > 16 )) && THREADS=16; (( THREADS < 2 )) && THREADS=2
fi

TS="$(date +%Y%m%d-%H%M%S)"
OUT="${OUT:-$HERE/results/$TS}"
# Absolute, because docker -v reads a relative source as a volume name.
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
RUNDIR="$OUT/run"
RAW="$OUT/raw"
mkdir -p "$RUNDIR/config" "$RUNDIR/journals" "$RAW"

# ----------------------------------------------------------------- preflight

need() { command -v "$1" >/dev/null 2>&1 || die "missing required tool: $1"; }

preflight() {
  need docker
  need python3
  need curl
  need ss
  [[ ",$TOOLS," == *",wrk,"*  ]] && need wrk
  [[ ",$TOOLS," == *",wrk2,"* ]] && need wrk2
  has "$SCENARIOS" sweep && need wrk2
  command -v wrk >/dev/null 2>&1 || command -v wrk2 >/dev/null 2>&1 \
    || die "need at least one of wrk / wrk2"

  if [[ "$MODE" == "jvm-local" ]]; then
    need java
    if [[ -z "$JAR" ]]; then
      JAR="$(ls -t "$REPO"/r7-helidon/target/r7-helidon-*.jar 2>/dev/null | grep -v sources | grep -v javadoc | head -n1 || true)"
    fi
    [[ -n "$JAR" && -f "$JAR" ]] || die "no gateway jar found; run 'mvn -q -DskipTests install' or pass --jar"
    log "jar: $JAR"
  fi

  (( SKIP_PREFLIGHT )) && return 0

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
  (( REPEAT < 3 )) && warn "--repeat is $REPEAT; the report cannot show a noise floor below 3"

  if [[ -n "$GATEWAY_CPUS" && -n "$LOAD_CPUS" ]]; then
    log "gateway on CPUs $GATEWAY_CPUS, load generator on $LOAD_CPUS: separate cores, shared cache and memory."
  else
    warn "load generator and gateway share this host: they compete for CPU."
  fi
  warn "'vs base' is an upper bound on cost. 'vs r7' is the clean number."
}

# ----------------------------------------------------------------- pinning

# As root on a systemd host with cgroup v2, each pinned process gets a transient scope in
# its own slice. That matters when bench.sh has confined system.slice and user.slice to the
# housekeeping cores: a process started from this shell inherits user.slice's cpuset, so a
# plain taskset to a benchmark core would be refused. Anywhere else, taskset.
PIN_METHOD="taskset"
if [[ $EUID -eq 0 && -d /run/systemd/system && -f /sys/fs/cgroup/cgroup.controllers ]] \
   && command -v systemd-run >/dev/null 2>&1; then
  PIN_METHOD="systemd"
fi

# Sets PIN to the command prefix that runs a program on the given CPUs (empty: unpinned).
# It is a prefix rather than a function so `exec` keeps the gateway's PID: both
# systemd-run --scope and taskset exec the command in place.
pin_prefix() {
  PIN=()
  [[ -z "$1" ]] && return 0
  if [[ "$PIN_METHOD" == "systemd" ]]; then
    PIN=(systemd-run --scope --quiet --collect --slice="$PIN_SLICE" -p AllowedCPUs="$1")
  else
    PIN=(taskset -c "$1")
  fi
}

# ----------------------------------------------------------------- gateway image

GW_IMAGE="${R7_BENCH_IMAGE:-ghcr.io/ethlo/r7-gateway:latest}"
# The container runs as the invoking user so it can write the bind-mounted journals. As
# root (bench.sh) that would measure the gateway with root privileges, so it runs as the
# image's own user instead and the journals directory is handed to that user.
GW_UID="$(id -u)"; GW_GID="$(id -g)"
if [[ "$MODE" == "docker" && $EUID -eq 0 ]]; then
  docker image inspect "$GW_IMAGE" >/dev/null 2>&1 || docker pull -q "$GW_IMAGE" >/dev/null \
    || die "cannot pull $GW_IMAGE"
  img_user="$(docker inspect -f '{{.Config.User}}' "$GW_IMAGE")"
  [[ "$img_user" =~ ^([0-9]+)(:([0-9]+))?$ ]] \
    || die "image user '$img_user' is not numeric uid[:gid]; cannot hand it the journals"
  GW_UID="${BASH_REMATCH[1]}"; GW_GID="${BASH_REMATCH[3]:-${BASH_REMATCH[1]}}"
fi

# ----------------------------------------------------------------- lifecycle

BACKEND_CID=""
GW_PID=""
GW_COMPOSE=0
COMPOSE_FILES=()
GW_LEVEL=""

# The compose file requires these for every command, down included: without them `down`
# fails to interpolate, the gateway keeps running, and the next `up` reuses the same JVM.
compose() {
  R7_BENCH_CONFIG="$RUNDIR/config" \
  R7_BENCH_JOURNALS="$RUNDIR/journals" \
  R7_BENCH_JVM_OPTS="$JVM_OPTS" \
  R7_BENCH_UID="$GW_UID" \
  R7_BENCH_GID="$GW_GID" \
    docker compose "${COMPOSE_FILES[@]}" "$@"
}

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
# INT/TERM exit, which runs cleanup once; trapping them to cleanup directly would resume the
# scenario loops afterwards.
trap cleanup EXIT
trap 'exit 130' INT TERM

wait_for() {
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
  local conf="$HERE/backend/nginx.conf"
  local -a pin_args=()
  if [[ -n "$BACKEND_CPUS" ]]; then
    # worker_processes auto counts the host's CPUs, not the container's cpuset.
    conf="$(cd "$RUNDIR" && pwd)/nginx.conf"
    sed -e "s|^worker_processes .*|worker_processes  $(cpu_count "$BACKEND_CPUS");|" \
      "$HERE/backend/nginx.conf" > "$conf"
    pin_args=(--cpuset-cpus "$BACKEND_CPUS")
    # With the systemd cgroup driver containers live under system.slice, which bench.sh
    # confines to the housekeeping cores; the cpuset above would then be ignored.
    if [[ "$PIN_METHOD" == "systemd" && "$(docker info -f '{{.CgroupDriver}}' 2>/dev/null)" == "systemd" ]]; then
      pin_args+=(--cgroup-parent "$PIN_SLICE")
    fi
  fi
  BACKEND_CID="$(docker run -d --rm \
    --name r7-bench-backend \
    --network host \
    --ulimit nofile=200000:200000 \
    "${pin_args[@]}" \
    -v "$conf:/etc/nginx/nginx.conf:ro" \
    "$BACKEND_IMAGE")"
  wait_for "http://127.0.0.1:$BACKEND_PORT/__bench_health" "backend"
  ok "backend ready"
}

render_config() {
  local level="$1"
  sed -e "s|__JOURNAL_LEVEL__|$level|g" \
      -e "s|__BACKEND_URL__|http://127.0.0.1:$BACKEND_PORT|g" \
      "$HERE/config/routes.yaml.tmpl" > "$RUNDIR/config/routes.yaml"

  local wd="$RUNDIR/journals"
  [[ "$MODE" == "docker" ]] && wd="/journals"
  sed -e "s|__WORK_DIR__|$wd|g" \
      "$HERE/config/server.yaml.tmpl" > "$RUNDIR/config/server.yaml"

  rm -rf "${RUNDIR:?}/journals"; mkdir -p "$RUNDIR/journals"
  if [[ "$MODE" == "docker" ]]; then chown "$GW_UID:$GW_GID" "$RUNDIR/journals"; fi
}

start_gateway() {
  local level="$1"
  render_config "$level"
  if [[ "$MODE" == "jvm-local" ]]; then
    log "starting gateway (jvm-local, journal=$level)"
    pin_prefix "$GATEWAY_CPUS"
    # r7 reads routes.yaml and server.yaml from its working directory.
    ( cd "$RUNDIR/config" && exec "${PIN[@]}" java $JVM_OPTS ${R7_ARGS:-} -jar "$JAR" ) \
      >> "$OUT/gateway-$level.log" 2>&1 &
    GW_PID=$!
  else
    log "starting gateway (docker, journal=$level)"
    GW_COMPOSE=1
    GW_LEVEL="$level"
    COMPOSE_FILES=(-f "$HERE/docker-compose.bench.yaml")
    if [[ -n "$GATEWAY_CPUS" ]]; then
      # Pinning is an override rather than part of the compose file, which has no way to
      # say "unpinned" with an empty value. The cgroup parent matters for the same reason
      # as the backend's.
      {
        echo "services:"
        echo "  gateway:"
        echo "    cpuset: \"$GATEWAY_CPUS\""
        if [[ "$PIN_METHOD" == "systemd" && "$(docker info -f '{{.CgroupDriver}}' 2>/dev/null)" == "systemd" ]]; then
          echo "    cgroup_parent: $PIN_SLICE"
        fi
      } > "$RUNDIR/compose.pin.yaml"
      COMPOSE_FILES+=(-f "$RUNDIR/compose.pin.yaml")
    fi
    compose up -d gateway
  fi
  wait_for "http://127.0.0.1:$GW_PORT/bench/__bench_health" "gateway" 120
  ok "gateway ready (journal=$level)"
}

stop_gateway() {
  if [[ -n "$GW_PID" ]]; then
    kill "$GW_PID" 2>/dev/null || true
    wait "$GW_PID" 2>/dev/null || true
    GW_PID=""
  fi
  if (( GW_COMPOSE )); then
    docker logs r7-bench-gateway >> "$OUT/gateway-$GW_LEVEL.log" 2>&1 || true
    # A gateway that survives here would be reused by the next `up`: the next run would
    # measure the old JVM and configuration. So a failed stop ends the run.
    if ! compose down --remove-orphans >/dev/null 2>&1; then
      warn "could not stop the gateway container"
      return 1
    fi
    GW_COMPOSE=0
  fi
  local listening
  for _ in {1..20}; do
    # A failed probe is not a free port.
    listening="$(ss -ltnH "sport = :$GW_PORT" 2>/dev/null)" \
      || { warn "ss failed; cannot confirm the gateway port is free"; return 1; }
    [[ -z "$listening" ]] && return 0
    sleep 0.25
  done
  warn "something still listens on :$GW_PORT after stopping the gateway"
  return 1
}

journal_size() {
  # Allocated bytes, not apparent size. Segments are pre-allocated sparse files and the
  # writer keeps several warmed ahead of itself, so -b (which implies --apparent-size)
  # reports segment_size x queue_depth regardless of how much was actually written.
  du -s --block-size=1 "$RUNDIR/journals" 2>/dev/null | awk '{print $1}' || echo 0
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

# fire <tool> <scenario> <workload> <journal> <rate> <url> <repeat-index> <do-warmup>
fire() {
  local tool="$1" scenario="$2" workload="$3" journal="$4" rate="$5" url="$6"
  local rep="$7" do_warmup="$8"
  local script; script="$(workload_script "$workload")"

  local -a PIN; pin_prefix "$LOAD_CPUS"
  local tag="${scenario}-${workload}-${tool}"
  [[ "$scenario" == "journal" ]] && tag="${scenario}-${journal}-${workload}-${tool}"
  [[ "$scenario" == "sweep"   ]] && tag="$(printf 'sweep-%s-%s-r%08d' "$journal" "$workload" "$rate")"
  local file_tag="${tag}.n${rep}"

  if (( do_warmup )); then
    # Warm at full tilt regardless of which tool measures: C2 needs volume, not
    # a fixed rate. Prefer wrk; fall back to wrk2 if only that is installed.
    log "warmup  ${tag} (${WARMUP}, discarded)"
    if command -v wrk >/dev/null 2>&1; then
      "${PIN[@]}" wrk -t"$THREADS" -c"$CONNECTIONS" -d"$WARMUP" -s "$script" --timeout 5s "$url" \
        > "$RAW/$file_tag.warmup.txt" 2>&1 || true
    else
      "${PIN[@]}" wrk2 -t"$THREADS" -c"$CONNECTIONS" -d"$WARMUP" -R"$((rate * 4))" -s "$script" \
        --timeout 5s "$url" > "$RAW/$file_tag.warmup.txt" 2>&1 || true
    fi
    sleep 2
  fi

  log "measure ${tag} (${DURATION}, repeat ${rep}/${REPEAT})"
  if [[ "$tool" == "wrk" ]]; then
    "${PIN[@]}" wrk -t"$THREADS" -c"$CONNECTIONS" -d"$DURATION" --latency --timeout 5s \
        -s "$script" "$url" > "$RAW/$file_tag.txt" 2>&1 || true
  else
    "${PIN[@]}" wrk2 -t"$THREADS" -c"$CONNECTIONS" -d"$DURATION" -R"$rate" --latency --timeout 5s \
         -s "$script" "$url" > "$RAW/$file_tag.txt" 2>&1 || true
  fi

  local meta
  meta="$(python3 - "$scenario" "$workload" "$tool" "$journal" "$MODE" "$rate" "$rep" <<'PY'
import json, sys
s, w, t, j, m, r, n = sys.argv[1:8]
print(json.dumps({"scenario": s, "workload": w, "tool": t, "journal": j,
                  "mode": m, "repeat": int(n),
                  "rate": int(r) if t == "wrk2" else None}))
PY
)"
  python3 "$HERE/lib/parse.py" parse "$RAW/$file_tag.txt" "$meta" > "$OUT/$file_tag.json"

  local rps; rps="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["stats"]["rps"] or 0)' "$OUT/$file_tag.json")"
  ok "  ${tag} [${rep}/${REPEAT}]: ${rps} req/s"
  sleep 3   # let sockets drain before the next run
}

# Run one gateway-backed scenario across workloads, tools and repeats.
# gw_scenario <scenario> <journal-level> <url> [rate]
gw_scenario() {
  local scenario="$1" level="$2" url="$3" rate="${4:-$RATE}"
  local -a wl tl
  IFS=',' read -ra wl <<< "$WORKLOADS"
  IFS=',' read -ra tl <<< "$TOOLS"
  [[ "$scenario" == "sweep" ]] && tl=(wrk2)

  if (( RESTART_PER_REPEAT )); then
    for ((rep = 1; rep <= REPEAT; rep++)); do
      start_gateway "$level"
      for w in "${wl[@]}"; do for t in "${tl[@]}"; do
        fire "$t" "$scenario" "$w" "$level" "$rate" "$url" "$rep" 1
      done; done
      echo "journal_bytes_${scenario}_${level}_n${rep}=$(journal_size)" >> "$OUT/environment.txt"
      stop_gateway
    done
  else
    start_gateway "$level"
    for w in "${wl[@]}"; do for t in "${tl[@]}"; do
      for ((rep = 1; rep <= REPEAT; rep++)); do
        # Warm once per (workload, tool); repeats measure run-to-run noise on an
        # already-hot JVM. Use --restart-per-repeat to include JIT variance too.
        fire "$t" "$scenario" "$w" "$level" "$rate" "$url" "$rep" "$(( rep == 1 ? 1 : 0 ))"
      done
    done; done
    echo "journal_bytes_${scenario}_${level}=$(journal_size)" >> "$OUT/environment.txt"
    stop_gateway
  fi
}

# ----------------------------------------------------------------- main

preflight
log "mode=$MODE threads=$THREADS conns=$CONNECTIONS duration=$DURATION warmup=$WARMUP"
log "rate=$RATE repeat=$REPEAT restart-per-repeat=$RESTART_PER_REPEAT"
log "scenarios=$SCENARIOS"
log "results -> $OUT"

{
  echo "mode=$MODE"
  echo "threads=$THREADS connections=$CONNECTIONS duration=$DURATION warmup=$WARMUP"
  echo "rate=$RATE repeat=$REPEAT restart_per_repeat=$RESTART_PER_REPEAT"
  echo "scenarios=$SCENARIOS workloads=$WORKLOADS tools=$TOOLS"
  echo "sweep_rates=$SWEEP_RATES sweep_levels=$SWEEP_LEVELS journal_levels=$JOURNAL_LEVELS"
  echo "host=$(uname -srm) cpus=$(getconf _NPROCESSORS_ONLN)"
  if [[ "$MODE" == "docker" ]]; then
    echo "image=$GW_IMAGE user=$GW_UID:$GW_GID"
  else
    echo "java=$(java -version 2>&1 | head -n1 || echo n/a)"
  fi
  echo "wrk=$(wrk --version 2>&1 | head -n1 || echo n/a)"
  echo "wrk2=$(wrk2 --version 2>&1 | head -n1 || echo n/a)"
  echo "jar=$JAR"
  echo "jvm_opts=$JVM_OPTS"
  echo "backend_image=$BACKEND_IMAGE"
  echo "pinning=$PIN_METHOD backend_cpus=${BACKEND_CPUS:-none} gateway_cpus=${GATEWAY_CPUS:-none} load_cpus=${LOAD_CPUS:-none}"
  echo "git=$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo n/a)"
  echo "date=$(date -Is)"
} > "$OUT/environment.txt"

start_backend

# 1. Baseline: straight at the backend. The floor.
if has "$SCENARIOS" baseline; then
  log "=== scenario: baseline (no gateway) ==="
  IFS=',' read -ra WL <<< "$WORKLOADS"
  IFS=',' read -ra TL <<< "$TOOLS"
  for w in "${WL[@]}"; do for t in "${TL[@]}"; do
    for ((rep = 1; rep <= REPEAT; rep++)); do
      fire "$t" baseline "$w" "-" "$RATE" \
        "http://127.0.0.1:$BACKEND_PORT/bench/api/v1/users" "$rep" "$(( rep == 1 ? 1 : 0 ))"
    done
  done; done
fi

# 2. Passthrough: r7 in the path, no filters, journal off.
#    This is the denominator for everything below it.
if has "$SCENARIOS" passthrough; then
  log "=== scenario: passthrough (r7, no filters, journal=NONE) ==="
  gw_scenario passthrough NONE "http://127.0.0.1:$GW_PORT/bench/api/v1/users"
fi

# 3. Filtered: the cost of the filter chain, journal still off.
if has "$SCENARIOS" filtered; then
  log "=== scenario: filtered (AddCorrelationId + header rewrites) ==="
  gw_scenario filtered NONE "http://127.0.0.1:$GW_PORT/filtered/api/v1/users"
fi

# 4. Journal cost matrix. NONE is deliberately absent: it is `passthrough`.
if has "$SCENARIOS" journal; then
  log "=== scenario: journal ($JOURNAL_LEVELS) ==="
  IFS=',' read -ra LEVELS <<< "$JOURNAL_LEVELS"
  for level in "${LEVELS[@]}"; do
    gw_scenario journal "$level" "http://127.0.0.1:$GW_PORT/bench/api/v1/users"
  done
fi

# 5. Rate sweep: p99 against offered load. The curve that characterises a
#    gateway, and the only way to find the knee rather than guess at it.
if has "$SCENARIOS" sweep; then
  log "=== scenario: sweep (wrk2, rates=$SWEEP_RATES, levels=$SWEEP_LEVELS) ==="
  IFS=',' read -ra SLEVELS <<< "$SWEEP_LEVELS"
  IFS=',' read -ra SRATES  <<< "$SWEEP_RATES"
  for level in "${SLEVELS[@]}"; do
    for r in "${SRATES[@]}"; do
      gw_scenario sweep "$level" "http://127.0.0.1:$GW_PORT/bench/api/v1/users" "$r"
    done
  done
fi

# ----------------------------------------------------------------- report

python3 "$HERE/lib/parse.py" report "$OUT" --format md  > "$OUT/REPORT.md"
python3 "$HERE/lib/parse.py" report "$OUT" --format tsv > "$OUT/summary.tsv"

echo
cat "$OUT/REPORT.md"
echo
ok "raw output:  $RAW"
ok "report:      $OUT/REPORT.md"
