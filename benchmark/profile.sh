#!/usr/bin/env bash
# Profile the gateway's request path: what one request costs in CPU, and where that CPU goes.
#
#   benchmark/profile.sh                                  # main's jar, api-get, journal NONE
#   benchmark/profile.sh --jar /tmp/a/r7-helidon-1.0-SNAPSHOT.jar --label a
#   benchmark/profile.sh --profile cpu,alloc,syscalls,jit # everything below
#   benchmark/profile.sh --poller-mode 3 --repeat 5
#
# Where run.sh answers "how fast", this answers "why": one gateway JVM, a fixed load, and the
# tools that explain its cost. It is the procedure behind design/history/get-path-profile.md.
#
# The number it reports is CPU per request, not throughput. On a small host the backend and
# the load generator saturate too, so once the gateway gets cheaper its req/s stops rising
# and only CPU per request shows the gain; it is also far less noisy (±3% against ±5-10%).
# It is the busy time of the gateway's CPUs (user + system + irq + softirq from /proc/stat)
# divided by the requests wrk completed. Counting whole CPUs rather than the process is on
# purpose: softirq time (loopback TCP delivery, mostly) lands on the CPU that caused it and
# is part of what proxying costs. That only holds when nothing else runs on those CPUs, so
# everything is pinned:
#
#   --backend-cpus   nginx, the benchmark's pinned image and config, one worker per CPU
#   --gateway-cpus   the gateway (taskset: the JVM sizes its GC and scheduler from these)
#   --load-cpus      wrk, one thread per CPU
#
# The defaults (0 / 1-2 / 3) fit a 4-CPU host. Compare variants on the same host, layout and
# load, interleaved (A B A B), each from a fresh JVM: --label names the rows for that.
#
# --profile adds, after the measured runs and against the same warm JVM:
#
#   cpu       async-profiler, CPU samples with kernel stacks. Kernel time is half or more of
#             a proxy's cost, so a Java-only profiler misses the main story. Needs
#             kernel.perf_event_paranoid <= 1 (the script says how) and $ASPROF or an
#             async-profiler under benchmark/.cache/async-profiler. Sampling at 1 ms adds
#             ~20% overhead: read shares from the profile, the cost from the measured runs.
#   alloc     async-profiler allocation samples: what the request path allocates, by site.
#   syscalls  strace -c over a short run: syscalls per request (a proxy needs ~2 reads and
#             ~2 writes; more means extra wakeups or partial I/O).
#   jit       a second JVM with -XX:+PrintInlining, then Compiler.codelist: inlining
#             failures on r7 and Helidon methods, and the size of each C2-compiled method.
#
# Each profile is summarised by lib/profile_summary.py; the raw files stay in --out for
# flame graphs (async-profiler's jfrconv, or any collapsed-stack viewer).
#
# Requirements: Linux, docker, taskset, python3, wrk (benchmark/.cache/bin from bench.sh, or
# PATH), JDK 27 on PATH; strace for syscalls. Build first: ./mvnw -q -DskipTests install.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

JAR=""
LABEL="r7"
WORKLOAD="api-get"
LEVEL="NONE"
CONNECTIONS=200
WARMUP=45          # seconds. C2 is still compiling at 20 s on two cores; the first rows rise
DURATION=15
REPEAT=3
POLLER_MODE=""     # empty: the gateway's own default (R7Helidon, mode 1)
BACKEND_CPUS=0
GATEWAY_CPUS=1-2
LOAD_CPUS=3
PROFILE=""
INTERVAL=1ms       # async-profiler CPU interval
OUT=""
EXTRA_JVM=()

usage() { sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; echo; sed -n '/^while/,/^done/p' "$0" | grep -E '^\s+--' | sed 's/).*//'; exit "${1:-0}"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar) JAR="$2"; shift 2 ;;
    --label) LABEL="$2"; shift 2 ;;
    --workload) WORKLOAD="$2"; shift 2 ;;
    --journal-level) LEVEL="$2"; shift 2 ;;
    --connections) CONNECTIONS="$2"; shift 2 ;;
    --warmup) WARMUP="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    --repeat) REPEAT="$2"; shift 2 ;;
    --poller-mode) POLLER_MODE="$2"; shift 2 ;;
    --backend-cpus) BACKEND_CPUS="$2"; shift 2 ;;
    --gateway-cpus) GATEWAY_CPUS="$2"; shift 2 ;;
    --load-cpus) LOAD_CPUS="$2"; shift 2 ;;
    --profile) PROFILE="$2"; shift 2 ;;
    --interval) INTERVAL="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --jvm-opt) EXTRA_JVM+=("$2"); shift 2 ;;
    -h|--help) usage 0 ;;
    *) echo "unknown option: $1" >&2; usage 2 ;;
  esac
done

die() { echo "profile.sh: $*" >&2; exit 1; }
log() { echo "== $*" >&2; }

# Ports of their own, so a run.sh or a dev gateway on 8888/11111 is not measured by mistake.
BACKEND_PORT=21111
GW_PORT=28888
GW_STATUS_PORT=38888
BACKEND_IMAGE="${R7_BENCH_BACKEND_IMAGE:-nginx:alpine@sha256:df221db836e1754089190208cee7eeda94f233197056426eda74a43ab1abeac2}"

[[ -n "$JAR" ]] || JAR="$(ls -t "$ROOT"/r7-helidon/target/r7-helidon-*.jar 2>/dev/null | grep -v -e sources -e javadoc | head -n1 || true)"
[[ -f "$JAR" ]] || die "no gateway jar; build with ./mvnw -q -DskipTests install or pass --jar"
JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
WRK="$(PATH="$HERE/.cache/bin:$PATH" command -v wrk || true)"
[[ -n "$WRK" ]] || die "wrk not found (benchmark/.cache/bin or PATH)"
for t in docker taskset python3 java; do command -v "$t" >/dev/null || die "$t not found"; done

case "$WORKLOAD" in
  browser) SCRIPT="$HERE/wrk/browser-get.lua"; PATH_SUFFIX="/app/dashboard" ;;
  api-get) SCRIPT="$HERE/wrk/api-get.lua"; PATH_SUFFIX="/api/v1/orders/1234" ;;
  api-post) SCRIPT="$HERE/wrk/api-post.lua"; PATH_SUFFIX="/api/v1/orders" ;;
  headers) SCRIPT="$HERE/wrk/header-heavy.lua"; PATH_SUFFIX="/app/dashboard" ;;
  *) die "unknown workload: $WORKLOAD" ;;
esac
URL="http://127.0.0.1:$GW_PORT/bench$PATH_SUFFIX"

# taskset lists ("1-2", "1,3") to a count, for nginx workers and wrk threads.
cpu_count() { python3 -c 'import sys; n=0
for p in sys.argv[1].split(","):
    a,_,b=p.partition("-"); n+=int(b or a)-int(a)+1
print(n)' "$1"; }
# The CPUs as /proc/stat names: "1-2" -> "cpu1 cpu2".
cpu_names() { python3 -c 'import sys; r=[]
for p in sys.argv[1].split(","):
    a,_,b=p.partition("-"); r+=["cpu%d"%c for c in range(int(a),int(b or a)+1)]
print(" ".join(r))' "$1"; }

OUT="${OUT:-$HERE/results/profile-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT/config" "$OUT/journals"
# Absolute: docker reads a relative -v source as a volume name, and the gateway runs in config/.
OUT="$(cd "$OUT" && pwd)"

# ------------------------------------------------------------------------------- processes

GW_PID=""
cleanup() {
  [[ -n "$GW_PID" ]] && kill "$GW_PID" 2>/dev/null && wait "$GW_PID" 2>/dev/null || true
  docker rm -f r7-profile-backend >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_for() {
  for _ in $(seq 1 60); do curl -sf -o /dev/null "$1" && return 0; sleep 0.5; done
  die "$2 did not come up ($1)"
}

start_backend() {
  sed -e "s|listen 11111 |listen $BACKEND_PORT |" \
      -e "s|^worker_processes .*|worker_processes  $(cpu_count "$BACKEND_CPUS");|" \
      "$HERE/backend/nginx.conf" > "$OUT/nginx.conf"
  docker rm -f r7-profile-backend >/dev/null 2>&1 || true   # left over from a killed run
  # No --ulimit: some container hosts refuse to raise RLIMIT_NOFILE, and 200 connections fit.
  docker run -d --rm --name r7-profile-backend --network host --cpuset-cpus "$BACKEND_CPUS" \
    -v "$OUT/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$HERE/backend/www:/srv/bench:ro" \
    "$BACKEND_IMAGE" >/dev/null
  wait_for "http://127.0.0.1:$BACKEND_PORT/__bench_health" backend
}

render_config() {
  sed -e "s|__JOURNAL_LEVEL__|$LEVEL|g" -e "s|__BACKEND_URL__|http://127.0.0.1:$BACKEND_PORT|g" \
      "$HERE/config/routes.yaml.tmpl" > "$OUT/config/routes.yaml"
  sed -e "s|__WORK_DIR__|$OUT/journals|g" -e "s|__GW_PORT__|$GW_PORT|g" -e "s|__GW_STATUS_PORT__|$GW_STATUS_PORT|g" \
      "$HERE/config/server.yaml.tmpl" > "$OUT/config/server.yaml"
}

# The image's flags, so the JIT and GC decisions match what ships (no AOT cache: it moves
# startup, not steady state).
start_gateway() {
  local log="$1"; shift
  local -a opts=(--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow)
  [[ -n "$POLLER_MODE" ]] && opts+=("-Djdk.pollerMode=$POLLER_MODE")
  # Something already answering on the port would be measured instead, silently.
  curl -s -o /dev/null --max-time 1 "$URL" && die "port $GW_PORT is already in use"
  ( cd "$OUT/config" && exec taskset -c "$GATEWAY_CPUS" java "${opts[@]}" "${EXTRA_JVM[@]}" "$@" -jar "$JAR" ) > "$log" 2>&1 &
  GW_PID=$!
  wait_for "$URL" gateway
  kill -0 "$GW_PID" 2>/dev/null || die "the gateway exited; see $log"
}

stop_gateway() {
  kill "$GW_PID" 2>/dev/null || true
  wait "$GW_PID" 2>/dev/null || true
  GW_PID=""
}

# ----------------------------------------------------------------------------- measurement

# busy and total jiffies over the gateway's CPUs: user nice system idle iowait irq softirq steal
stat_sample() {
  awk -v cpus=" $(cpu_names "$GATEWAY_CPUS") " 'index(cpus, " " $1 " ") {
    usr += $2 + $3; sys += $4; irq += $7 + $8 } END { print usr, sys, irq }' /proc/stat
}

# One wrk run against the gateway; prints a tab-separated row and keeps wrk's output.
measure() {
  local name="$1" secs="$2" raw="$OUT/$1.wrk.txt"
  local u0 s0 i0 u1 s1 i1
  read -r u0 s0 i0 < <(stat_sample)
  taskset -c "$LOAD_CPUS" "$WRK" -t"$(cpu_count "$LOAD_CPUS")" -c"$CONNECTIONS" -d"${secs}s" \
    --latency -s "$SCRIPT" "$URL" > "$raw" 2>&1
  read -r u1 s1 i1 < <(stat_sample)
  python3 - "$raw" "$LABEL" "$name" "$secs" "$((u1 - u0))" "$((s1 - s0))" "$((i1 - i0))" "$(getconf CLK_TCK)" <<'EOF'
import re, sys
raw, label, name, secs, u, s, i, hz = sys.argv[1:]
text = open(raw).read()
req = int(re.search(r"(\d+) requests in", text).group(1))
p = dict(re.findall(r"^\s+(50|99)%\s+(\S+)", text, re.M))
bad = re.search(r"Non-2xx or 3xx responses: (\d+)", text)
errors = re.search(r"Socket errors: (.*)", text)
us = lambda j: int(j) / int(hz) * 1e6 / req
print("\t".join([label, name, "%.0f" % (req / float(secs)), p.get("50", "-"), p.get("99", "-"),
                 "%.1f" % (us(u) + us(s) + us(i)), "%.1f" % us(u), "%.1f" % us(s), "%.1f" % us(i),
                 "%.2f" % ((int(u) + int(s) + int(i)) / int(hz) / float(secs)),
                 (bad.group(1) + " non-2xx " if bad else "") + (errors.group(1) if errors else "")]))
EOF
}

# --------------------------------------------------------------------------------- profiles

asprof_bin() {
  local p="${ASPROF:-$HERE/.cache/async-profiler/bin/asprof}"
  [[ -x "$p" ]] || die "async-profiler not found: set ASPROF, or unpack a release into benchmark/.cache/async-profiler"
  echo "$p"
}

check_perf_events() {
  local level; level="$(cat /proc/sys/kernel/perf_event_paranoid)"
  if (( level > 1 )); then
    die "kernel.perf_event_paranoid is $level; kernel stacks need <= 1: sudo sysctl kernel.perf_event_paranoid=1 kernel.kptr_restrict=0"
  fi
}

# Runs a profiler in the background over a load run of the same length.
profile_async() {
  local event="$1" file="$2"; shift 2
  "$(asprof_bin)" -e "$event" "$@" -d "$((DURATION + 1))" -o collapsed -f "$file" "$GW_PID" >/dev/null &
  local prof=$!
  sleep 1
  measure "profiled-$event" "$DURATION" > /dev/null
  wait "$prof"
}

profile_syscalls() {
  command -v strace >/dev/null || die "strace not found"
  local secs=7
  # -f follows every thread; counts only. strace slows the gateway a lot, which is fine:
  # this is about how many syscalls a request makes, not how long they take.
  timeout $((secs + 3)) strace -c -f -p "$GW_PID" -o "$OUT/syscalls.txt" 2> "$OUT/strace.err" &
  local tracer=$!
  sleep 1
  local raw="$OUT/syscalls.wrk.txt"
  taskset -c "$LOAD_CPUS" "$WRK" -t1 -c"$CONNECTIONS" -d"${secs}s" -s "$SCRIPT" "$URL" > "$raw" 2>&1
  wait "$tracer" || true
  # strace writes its table while it detaches from every thread, after timeout has returned.
  for _ in $(seq 1 20); do grep -q ' total$' "$OUT/syscalls.txt" 2>/dev/null && break; sleep 0.5; done
  grep -q ' total$' "$OUT/syscalls.txt" 2>/dev/null \
    || die "strace produced no table ($(head -c 300 "$OUT/strace.err")); attaching needs root or kernel.yama.ptrace_scope=0"
  python3 "$HERE/lib/profile_summary.py" syscalls "$OUT/syscalls.txt" "$(grep -oE '^ *[0-9]+ requests' "$raw" | grep -oE '[0-9]+')"
}

profile_jit() {
  log "jit: a fresh JVM with -XX:+PrintInlining"
  stop_gateway
  start_gateway "$OUT/jit.log" -XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining
  measure jit-warmup "$WARMUP" > /dev/null
  measure jit "$DURATION" > /dev/null
  jcmd "$GW_PID" Compiler.codelist > "$OUT/codelist.txt"
  python3 "$HERE/lib/profile_summary.py" jit "$OUT/jit.log" "$OUT/codelist.txt"
}

# ------------------------------------------------------------------------------------- run

{
  echo "date=$(date -u +%FT%TZ)"
  echo "jar=$JAR"
  echo "git=$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo n/a)$(git -C "$ROOT" diff --quiet 2>/dev/null || echo -dirty)"
  echo "java=$(java -version 2>&1 | grep -v Picked | head -n1)"
  echo "cpu=$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | xargs) nproc=$(nproc) kernel=$(uname -r)"
  echo "layout backend=$BACKEND_CPUS gateway=$GATEWAY_CPUS load=$LOAD_CPUS"
  echo "workload=$WORKLOAD level=$LEVEL connections=$CONNECTIONS warmup=${WARMUP}s duration=${DURATION}s repeat=$REPEAT poller_mode=${POLLER_MODE:-default}"
  echo "jvm_extra=${EXTRA_JVM[*]:-}"
} > "$OUT/environment.txt"

[[ "$PROFILE" == *cpu* || "$PROFILE" == *alloc* ]] && asprof_bin >/dev/null
[[ "$PROFILE" == *cpu* ]] && check_perf_events

log "backend on CPUs $BACKEND_CPUS"
start_backend
render_config
log "gateway on CPUs $GATEWAY_CPUS: $JAR"
start_gateway "$OUT/gateway.log"
log "warmup ${WARMUP}s"
measure warmup "$WARMUP" > /dev/null

HEADER=$'label\trun\treq/s\tp50\tp99\tcpu_us/req\tusr\tsys\tirq\tcores\terrors'
echo "$HEADER" > "$OUT/summary.tsv"
for r in $(seq 1 "$REPEAT"); do
  measure "run$r" "$DURATION" | tee -a "$OUT/summary.tsv"
done | { echo "$HEADER"; cat; } \
  | awk -F'\t' '{ printf "%-10s %-6s %8s %9s %9s %11s %6s %6s %6s %6s %s\n", $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11 }'

IFS=',' read -r -a WANT <<< "$PROFILE"
for p in "${WANT[@]}"; do
  case "$p" in
    cpu)
      log "cpu: async-profiler at $INTERVAL with kernel stacks"
      profile_async cpu "$OUT/cpu.collapsed" -i "$INTERVAL"
      python3 "$HERE/lib/profile_summary.py" cpu "$OUT/cpu.collapsed" > "$OUT/cpu.summary.txt" ;;
    alloc)
      log "alloc: async-profiler allocation samples"
      profile_async alloc "$OUT/alloc.collapsed"
      python3 "$HERE/lib/profile_summary.py" alloc "$OUT/alloc.collapsed" > "$OUT/alloc.summary.txt" ;;
    syscalls)
      log "syscalls: strace -c"
      profile_syscalls > "$OUT/syscalls.summary.txt" ;;
    jit)
      # Not in a pipeline: profile_jit replaces the gateway, and cleanup must see the new PID.
      profile_jit > "$OUT/jit.summary.txt" ;;
    "") continue ;;
    *) die "unknown profile: $p (cpu, alloc, syscalls, jit)" ;;
  esac
  cat "$OUT/$p.summary.txt"
done

log "results in $OUT"
