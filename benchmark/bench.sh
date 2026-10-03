#!/usr/bin/env bash
#
# One command for a benchmark run that can be repeated and quoted.
#
#   sudo benchmark/bench.sh             # full run of the published image on a tuned host
#   sudo benchmark/bench.sh --quick     # same pipeline with short runs, as a sanity check
#   sudo benchmark/bench.sh --local     # build and measure this checkout instead
#   benchmark/bench.sh --check          # no changes: show the core layout and host state
#   sudo benchmark/bench.sh --restore   # undo host tuning left behind by a killed run
#
# By default it measures the published gateway image, resolved to its digest. It builds wrk
# and wrk2 from pinned commits, tunes the host for the run (performance governor, turbo and
# SMT off, every other process confined to housekeeping cores), pins nginx, the gateway and
# the load generator to separate cores, runs run.sh with a fixed profile and puts the host
# back. Every host change is a runtime one, so a reboot also undoes it.
#
# --local instead downloads and verifies a pinned JDK, builds the checkout and trains its AOT
# cache the way the image does, and runs the jar on the host.
#
# See README.md for what the numbers mean.

set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
CACHE="$HERE/.cache"
STATE_DIR="/run/r7-bench"
STATE="$STATE_DIR/host-before.txt"

# ----------------------------------------------------------------- pinned toolchain

# The image runs on JDK 25 and CI builds with JDK 27; the benchmark does the same.
JDK25_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz"
JDK25_SHA256="dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e"
JDK27_URL="https://github.com/adoptium/temurin27-binaries/releases/download/jdk-27%2B35/OpenJDK27U-jdk_x64_linux_hotspot_27_35.tar.gz"
JDK27_SHA256="1cf69a4848ffb728b3b260dfd45206a51566ab571a02a30092271d4c580bccbc"
WRK_REPO="https://github.com/wg/wrk"
WRK_COMMIT="a211dd5a7050b1f9e8a9870b95513060e72ac4a0"
WRK2_REPO="https://github.com/giltene/wrk2"
WRK2_COMMIT="44a94c17d8e6a0bac8559b53da76848e430cb7a7"

# A run is publishable only if throughput repeats agree within this many percent.
MAX_SPREAD=5

# ----------------------------------------------------------------- options

IMAGE="ghcr.io/ethlo/r7-gateway:latest"
LOCAL=0
JDK=""
GC="default"
QUICK=0
ACTION="run"

c_red=$'\033[31m'; c_grn=$'\033[32m'; c_yel=$'\033[33m'; c_dim=$'\033[2m'; c_off=$'\033[0m'
log()  { printf '%s[bench]%s %s\n' "$c_dim" "$c_off" "$*" >&2; }
ok()   { printf '%s[bench]%s %s\n' "$c_grn" "$c_off" "$*" >&2; }
warn() { printf '%s[bench]%s %s\n' "$c_yel" "$c_off" "$*" >&2; }
die()  { printf '%s[bench]%s %s\n' "$c_red" "$c_off" "$*" >&2; exit 1; }

usage() {
  sed -n '3,19p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  cat <<'EOF'

Options:
  --image REF      gateway image to measure (default: ghcr.io/ethlo/r7-gateway:latest)
  --local          build this checkout and run the jar on the host instead of an image
  --quick          short runs, browser workload only; never publishable
  --jdk 25|27      with --local: JDK the jar runs on (default: 25, what the image ships)
  --gc default|zgc collector (default: the JVM's choice, as in the image)
  --check          print the core layout and current host state, change nothing
  --restore        restore host settings saved by an interrupted run
  -h, --help       this
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --image)   IMAGE="$2"; shift 2 ;;
    --local)   LOCAL=1; shift ;;
    --quick)   QUICK=1; shift ;;
    --jdk)     JDK="$2"; shift 2 ;;
    --gc)      GC="$2"; shift 2 ;;
    --check)   ACTION="check"; shift ;;
    --restore) ACTION="restore"; shift ;;
    -h|--help) usage; exit 0 ;;
    *)         die "unknown option: $1 (try --help)" ;;
  esac
done

if (( LOCAL )); then
  JDK="${JDK:-25}"
  [[ "$JDK" == "25" || "$JDK" == "27" ]] || die "--jdk must be 25 or 27"
else
  [[ -z "$JDK" ]] || die "--jdk applies to --local; an image brings its own JDK"
fi
[[ "$GC" == "default" || "$GC" == "zgc" ]] || die "--gc must be default or zgc"

# ----------------------------------------------------------------- host state

# Each line of the state file is "kind|target|original value"; restore applies them in
# reverse, so SMT comes back last, after the governors of the CPUs that stayed online.
save() { printf '%s|%s|%s\n' "$1" "$2" "$3" >> "$STATE"; }

# Whatever a killed run left behind: its pinned scopes and its containers. Stopped before the
# host is restored, so nothing keeps measuring on an untuned host.
stop_leftovers() {
  local pid
  pid="$(cat "$STATE_DIR/run.pid" 2>/dev/null || true)"
  if [[ -n "$pid" ]] && grep -qs 'run.sh' "/proc/$pid/cmdline"; then
    log "stopping the run.sh left by an interrupted run"
    kill -TERM "$pid" 2>/dev/null || true
    for _ in {1..60}; do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$pid" 2>/dev/null && { warn "run.sh ($pid) did not stop"; return 1; }
  fi
  rm -f "$STATE_DIR/run.pid"
  if systemctl is-active --quiet "r7bench.slice" 2>/dev/null; then
    systemctl stop r7bench.slice || { warn "could not stop r7bench.slice"; return 1; }
  fi
  docker rm -f r7-bench-gateway r7-bench-backend >/dev/null 2>&1 || true
  local running
  running="$(docker ps -q --filter name='^r7-bench-' 2>/dev/null)" \
    || { warn "cannot list containers to confirm the benchmark ones are gone"; return 1; }
  [[ -z "$running" ]] || { warn "benchmark containers are still running"; return 1; }
}

# A setting that fails to restore stays in the state file, so --restore can retry it.
restore_host() {
  [[ -f "$STATE" ]] || return 0
  # Restoring under a still-running benchmark would leave it measuring an untuned host and
  # throw away the snapshot needed to try again.
  stop_leftovers || { warn "keeping $STATE; stop the processes above and run --restore"; return 1; }
  log "restoring host settings"
  local kind target value line restored=0
  local -a failed=()
  while IFS= read -r line; do
    IFS='|' read -r kind target value <<< "$line"
    case "$kind" in
      sysfs)   { echo "$value" > "$target"; } 2>/dev/null && restored=1 || restored=0 ;;
      sysctl)  sysctl -qw "$target=$value" && restored=1 || restored=0 ;;
      cpuset)  systemctl set-property --runtime "$target" "AllowedCPUs=$value" && restored=1 || restored=0 ;;
      *)       restored=0 ;;
    esac
    (( restored )) || { warn "could not restore $kind $target to '$value'"; failed=("$line" "${failed[@]}"); }
  done < <(tac "$STATE")
  if (( ${#failed[@]} )); then
    printf '%s\n' "${failed[@]}" > "$STATE"
    warn "${#failed[@]} setting(s) not restored; they are kept in $STATE for 'bench.sh --restore'"
    return 1
  fi
  rm -f "$STATE"
  ok "host settings restored"
}

# One run or restore at a time: a second run would overwrite the first one's saved originals.
# Only this script holds the lock (children get fd 9 closed), so a killed run frees it at once
# and --restore stops what the run left behind.
lock_host() {
  mkdir -p "$STATE_DIR"
  exec 9> "$STATE_DIR/lock"
  flock -n 9 || die "another bench.sh run or restore holds $STATE_DIR/lock"
}

TUNING=()      # what was applied, for the record
UNTUNED=()     # what could not be, which makes the run unpublishable

set_sysfs() {
  local path="$1" value="$2" what="$3"
  if [[ ! -w "$path" ]]; then
    UNTUNED+=("$what: $path not available"); return 0
  fi
  local old; old="$(cat "$path")"
  if [[ "$old" != "$value" ]]; then
    save sysfs "$path" "$old"
    echo "$value" > "$path" 2>/dev/null || { UNTUNED+=("$what: writing $path failed"); return 0; }
  fi
}

smt_off() {
  local ctl=/sys/devices/system/cpu/smt/control
  local state; state="$(cat "$ctl" 2>/dev/null || echo notsupported)"
  case "$state" in
    on)                        set_sysfs "$ctl" off "SMT off"; TUNING+=("smt=off") ;;
    off|forceoff|notsupported) TUNING+=("smt=$state") ;;
    *)                         UNTUNED+=("SMT off: control is '$state'") ;;
  esac
}

turbo_off() {
  if [[ -e /sys/devices/system/cpu/intel_pstate/no_turbo ]]; then
    set_sysfs /sys/devices/system/cpu/intel_pstate/no_turbo 1 "turbo off"
  elif [[ -e /sys/devices/system/cpu/cpufreq/boost ]]; then
    set_sysfs /sys/devices/system/cpu/cpufreq/boost 0 "turbo off"
  else
    UNTUNED+=("turbo off: no intel_pstate/no_turbo or cpufreq/boost"); return 0
  fi
  TUNING+=("turbo=off")
}

governor_performance() {
  local f found=0
  for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_governor; do
    [[ -e "$f" ]] || continue
    found=1
    set_sysfs "$f" performance "performance governor"
  done
  (( found )) && TUNING+=("governor=performance") || UNTUNED+=("performance governor: no cpufreq")
}

tune_sysctls() {
  local kv key value old
  for kv in "net.ipv4.ip_local_port_range=1024 65535" "net.ipv4.tcp_tw_reuse=1" "net.core.somaxconn=65535"; do
    key="${kv%%=*}"; value="${kv#*=}"
    old="$(sysctl -n "$key" 2>/dev/null | tr '\t' ' ')" || { UNTUNED+=("sysctl $key unavailable"); continue; }
    if [[ "$old" != "$value" ]]; then
      save sysctl "$key" "$old"
      sysctl -qw "$key=$value" || UNTUNED+=("sysctl $key failed")
    fi
  done
  TUNING+=("sysctls")
}

# Confine everything else on the host to the housekeeping cores. systemd moves the existing
# processes of these units at once; run.sh starts its own processes in a separate slice.
isolate() {
  local os_cpus="$1" unit old
  if [[ ! -d /run/systemd/system || ! -f /sys/fs/cgroup/cgroup.controllers ]]; then
    UNTUNED+=("core isolation: needs systemd with cgroup v2"); return 0
  fi
  # With the cgroupfs driver, containers live outside the slices confined below and would
  # keep running on the benchmark cores.
  if [[ "$(docker info -f '{{.CgroupDriver}}' 2>/dev/null)" != "systemd" ]]; then
    UNTUNED+=("core isolation: Docker must use the systemd cgroup driver"); return 0
  fi
  # Every active top-level slice (children of -.slice have no dash in their name) except the
  # benchmark's own, so a custom slice such as a --cgroup-parent=workload.slice is confined too.
  local -a units=(init.scope)
  local slices
  slices="$(systemctl list-units --type=slice --state=active --no-legend --plain 2>/dev/null)" \
    || { UNTUNED+=("core isolation: cannot list slices"); return 0; }
  for unit in $(awk '{print $1}' <<< "$slices"); do
    [[ "$unit" == "-.slice" || "$unit" == "r7bench.slice" || "$unit" == *-* ]] || units+=("$unit")
  done
  CONFINED=" "
  for unit in "${units[@]}"; do
    [[ "$(systemctl show -p ActiveState --value "$unit" 2>/dev/null)" == "active" ]] || continue
    old="$(systemctl show -p AllowedCPUs --value "$unit")"
    save cpuset "$unit" "$old"
    systemctl set-property --runtime "$unit" "AllowedCPUs=$os_cpus" \
      || { UNTUNED+=("core isolation: $unit refused AllowedCPUs"); return 0; }
    CONFINED+="$unit "
  done
  # A top-level cgroup that is not one of the units above (made directly in cgroupfs, or a
  # slice that became active since the listing) is out of reach of AllowedCPUs, and anything
  # running in it can still use the benchmark cores. r7bench.slice is checked too: run.sh has
  # not started yet, so anything in it was left there by something else.
  local dir name
  for dir in /sys/fs/cgroup/*/; do
    name="$(basename "$dir")"
    [[ "$CONFINED" == *" $name "* ]] && continue
    if grep -qsx 'populated 1' "$dir/cgroup.events"; then
      UNTUNED+=("core isolation: processes in the top-level cgroup $name, which is not confined")
    fi
  done
  TUNING+=("isolation=systemd(os=$os_cpus)")
  ISOLATED=1
}

# The scan above sees the host as it was before the run. A top-level slice first activated
# during measurement is not confined, and could use the benchmark cores unseen; this samples
# every second until the given process ends and appends each one found populated to a file.
# r7bench.slice is skipped: run.sh's own processes are in it, pinned by run.sh.
watch_unconfined() {
  local watched="$1" out="$2" dir name
  while kill -0 "$watched" 2>/dev/null; do
    for dir in /sys/fs/cgroup/*/; do
      name="$(basename "$dir")"
      [[ "$CONFINED" == *" $name "* || "$name" == "r7bench.slice" ]] && continue
      grep -qsx 'populated 1' "$dir/cgroup.events" && echo "$name" >> "$out"
    done
    sleep 1
  done
}

host_report() {
  echo "cpu=$(lscpu | sed -n 's/^Model name:[[:space:]]*//p' | head -n1)"
  echo "kernel=$(uname -r)"
  echo "cmdline=$(cat /proc/cmdline)"
  echo "isolcpus=$(cat /sys/devices/system/cpu/isolated 2>/dev/null)"
  echo "smt=$(cat /sys/devices/system/cpu/smt/control 2>/dev/null || echo n/a)"
  echo "no_turbo=$(cat /sys/devices/system/cpu/intel_pstate/no_turbo 2>/dev/null || echo n/a)"
  echo "boost=$(cat /sys/devices/system/cpu/cpufreq/boost 2>/dev/null || echo n/a)"
  echo "governors=$(cat /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_governor 2>/dev/null | sort | uniq -c | xargs)"
  echo "online=$(cat /sys/devices/system/cpu/online)"
  echo "memory=$(free -g | awk '/^Mem:/ {print $2 " GiB"}')"
}

# ----------------------------------------------------------------- toolchain

# Run as the invoking user, so the build and the cache do not end up owned by root.
as_user() {
  if [[ $EUID -eq 0 && -n "${SUDO_USER:-}" ]]; then
    sudo -u "$SUDO_USER" -H env PATH="$PATH" "$@"
  else
    "$@"
  fi
}

# The marker is written only after a complete extraction, and names the archive's checksum,
# so an interrupted extraction or a changed pin replaces the directory instead of reusing it.
fetch_jdk() {
  local url="$1" sha="$2" dir="$3"
  [[ "$(cat "$dir/.sha256" 2>/dev/null)" == "$sha" ]] && return 0
  local tgz="$CACHE/dl/$(basename "$url")"
  log "downloading $(basename "$url")"
  as_user rm -rf "$dir"
  as_user mkdir -p "$CACHE/dl" "$dir"
  as_user curl -fsSL -o "$tgz" "$url"
  echo "$sha  $tgz" | sha256sum -c --quiet - || { rm -f "$tgz"; die "checksum mismatch for $tgz"; }
  as_user tar -xzf "$tgz" -C "$dir" --strip-components=1
  as_user sh -c "echo $sha > '$dir/.sha256'"
}

build_wrk() {
  local name="$1" repo="$2" commit="$3" bin="$CACHE/bin/$1"
  if [[ -x "$bin" && "$(cat "$bin.commit" 2>/dev/null)" == "$commit" ]]; then return 0; fi
  log "building $name at ${commit:0:12}"
  local src="$CACHE/src/$name"
  as_user rm -rf "$src"
  as_user mkdir -p "$src" "$CACHE/bin"
  as_user git -C "$src" init -q
  as_user git -C "$src" fetch -q --depth 1 "$repo" "$commit"
  as_user git -C "$src" checkout -q FETCH_HEAD
  as_user make -C "$src" -j"$(nproc)" > "$CACHE/$name-build.log" 2>&1 \
    || die "building $name failed; see $CACHE/$name-build.log"
  as_user cp "$src/wrk" "$bin"
  as_user sh -c "echo $commit > '$bin.commit'"
}

toolchain() {
  for t in curl git make gcc python3 docker lscpu sha256sum; do
    command -v "$t" >/dev/null 2>&1 || die "missing required tool: $t"
  done
  [[ -f /usr/include/openssl/ssl.h ]] || die "wrk2 needs the OpenSSL headers: apt install libssl-dev"
  as_user mkdir -p "$CACHE"
  if (( LOCAL )); then
    fetch_jdk "$JDK27_URL" "$JDK27_SHA256" "$CACHE/jdk-27"
    [[ "$JDK" == "25" ]] && fetch_jdk "$JDK25_URL" "$JDK25_SHA256" "$CACHE/jdk-25"
  fi
  build_wrk wrk  "$WRK_REPO"  "$WRK_COMMIT"
  build_wrk wrk2 "$WRK2_REPO" "$WRK2_COMMIT"
}

# ----------------------------------------------------------------- actions

[[ "$(uname -m)" == "x86_64" ]] || die "the pinned toolchain is x86_64 only"

if [[ "$ACTION" == "restore" ]]; then
  [[ $EUID -eq 0 ]] || die "--restore needs root"
  lock_host
  [[ -f "$STATE" ]] || { ok "nothing to restore"; exit 0; }
  restore_host
  exit 0
fi

if [[ "$ACTION" == "check" ]]; then
  log "core layout (computed now; the run computes it again after turning SMT off):"
  python3 "$HERE/lib/layout.py" | sed 's/^/  /' >&2
  log "host state:"
  host_report | sed 's/^/  /' >&2
  [[ -f "$STATE" ]] && warn "saved settings from an interrupted run exist; run --restore"
  exit 0
fi

[[ $EUID -eq 0 ]] || die "run with sudo: tuning the host needs root (try --check first)"

# Nothing inherited may change what is measured: r7 prefers these over the generated config,
# the JVM reads the option variables, and run.sh and the compose file read the R7_BENCH_
# ones. A backend image override is allowed, and gated in the verdict.
unset R7_ROUTES_CONFIG R7_SERVER_CONFIG R7_LOGBACK_CONFIG R7_ARGS \
  JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS BENCH_BODY_BYTES \
  R7_BENCH_IMAGE R7_BENCH_MEM R7_BENCH_MEM_RESERVE R7_BENCH_JVM_OPTS
lock_host
[[ -f "$STATE" ]] && die "settings from an interrupted run are still saved; run --restore first"

toolchain
export PATH="$CACHE/bin:$PATH"

# Untracked files count: Maven would build an untracked source too, and the benchmark's own
# scripts and config come from this checkout either way. Ignored ones (target/, .cache/,
# results/) do not.
DIRTY="$(git -C "$REPO" status --porcelain --untracked-files=normal)"
SHA="$(git -C "$REPO" rev-parse --short HEAD)"

JVM_GC=""
[[ "$GC" == "zgc" ]] && JVM_GC="-XX:+UseZGC"

if (( LOCAL )); then
  RUN_JDK="$CACHE/jdk-$JDK"
  log "building r7 with JDK 27"
  ( cd "$REPO" && as_user env JAVA_HOME="$CACHE/jdk-27" \
      ./mvnw -q -DskipTests -Dmaven.javadoc.skip=true -Dmaven.source.skip=true clean package -pl r7-helidon -am )
  JAR="$(ls "$REPO"/r7-helidon/target/r7-helidon-*[0-9T].jar | head -n1)"
  [[ -f "$JAR" ]] || die "no gateway jar after the build"

  # The image's JVM flags, and its AOT cache trained the same way Dockerfile.jvm does. The
  # cache only fits the JDK, jars and flags it was made with, so it is made here, per run.
  JVM_FLAGS="$JVM_GC --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -Djava.security.egd=file:/dev/./urandom"
  TRAIN="$(as_user mktemp -d)"
  log "training the AOT cache on JDK $JDK"
  ( cd "$TRAIN" && as_user env R7_ROUTES_CONFIG="$REPO/docker/aot-training/routes.yaml" \
      R7_JOURNAL_DIR="$TRAIN/journals" \
      "$RUN_JDK/bin/java" -XX:AOTCacheOutput="$TRAIN/r7.aot" $JVM_FLAGS -Dr7.aot.training=true -jar "$JAR" ) \
    > "$TRAIN/training.log" 2>&1 || die "AOT training failed; see $TRAIN/training.log"
  [[ -f "$TRAIN/r7.aot" ]] || die "AOT training wrote no cache; see $TRAIN/training.log"
  GATEWAY="r7 $SHA (local build, JDK $JDK)"
  ID="$SHA"
else
  log "pulling $IMAGE"
  # A local image (./build.sh tags r7-gateway) is fine to measure, but has no registry digest.
  PULLED=1
  docker pull -q "$IMAGE" >/dev/null 2>&1 || PULLED=0
  (( PULLED )) || docker image inspect "$IMAGE" >/dev/null 2>&1 || die "cannot pull $IMAGE"
  # Measure exactly one image: the digest it resolved to, recorded, so a later run can pull
  # the same bytes even after the tag has moved.
  # The repository is the reference without digest or tag; a tag can only follow the last
  # slash, so a registry port (host:5000/...) stays.
  REPO_NAME="${IMAGE%@*}"
  [[ "${REPO_NAME##*/}" == *:* ]] && REPO_NAME="${REPO_NAME%:*}"
  # Docker records Docker Hub repositories in their short form (nginx, not
  # docker.io/library/nginx), so the reference is compared in that form too.
  REPO_NAME="${REPO_NAME#docker.io/}"; REPO_NAME="${REPO_NAME#index.docker.io/}"
  REPO_NAME="${REPO_NAME#library/}"
  IMAGE_REF="$(docker inspect -f '{{range .RepoDigests}}{{println .}}{{end}}' "$IMAGE" \
    | awk -v p="$REPO_NAME@" 'index($0, p) == 1 { print; exit }')"
  IMAGE_JAVA="$(docker run --rm --entrypoint java "${IMAGE_REF:-$IMAGE}" -version 2>&1 | sed -n 2p)"
  # Only a repository digest lets someone else fetch the same bytes: a local image, or a pull
  # whose digest could not be matched to the reference, is recorded by its image ID instead.
  if [[ -z "$IMAGE_REF" ]]; then
    IMAGE_REF="$(docker inspect -f '{{.Id}}' "$IMAGE")"
    NO_DIGEST=1
  fi
  (( PULLED )) || NO_DIGEST=1
  log "image: $IMAGE_REF"
  GATEWAY="$IMAGE_REF"
  ID="$(sed 's/.*sha256://' <<< "$IMAGE_REF" | cut -c1-12)"
fi

# From here on the host is changed; the trap puts it back however the run ends.
: > "$STATE"
trap restore_host EXIT
trap 'exit 130' INT TERM
ulimit -n 65535 2>/dev/null || UNTUNED+=("ulimit -n 65535 refused (hard limit $(ulimit -Hn))")

smt_off
LAYOUT="$(python3 "$HERE/lib/layout.py")" || die "cannot derive a core layout"
eval "$LAYOUT"
turbo_off
governor_performance
tune_sysctls
isolate "$OS_CPUS"
[[ -n "$(cat /sys/devices/system/cpu/isolated 2>/dev/null)" ]] && TUNING+=("isolcpus=$(cat /sys/devices/system/cpu/isolated)")

log "layout: housekeeping=$OS_CPUS backend=$BACKEND_CPUS gateway=$GATEWAY_CPUS load=$LOAD_CPUS"
for u in "${UNTUNED[@]}"; do warn "not tuned: $u"; done

TS="$(date +%Y%m%d-%H%M%S)"
OUT="$HERE/results/$TS"
PROFILE=(--repeat 3 --restart-per-repeat --scenario baseline,passthrough,filtered,journal,sweep)
QUICK_FLAG=""
# --quick keeps the whole pipeline (every scenario, fresh JVMs) with short runs, the browser
# workload and one repeat; later options win in run.sh.
(( QUICK )) && PROFILE+=(--quick --repeat 1) && QUICK_FLAG=" --quick"

# In the background so a TERM to this script reaches run.sh at once; in the foreground bash
# would hold the signal until run.sh finished, hours later, with the host still tuned.
PINS=(--backend-cpus "$BACKEND_CPUS" --gateway-cpus "$GATEWAY_CPUS" --load-cpus "$LOAD_CPUS")
if (( LOCAL )); then
  PATH="$RUN_JDK/bin:$PATH" JVM_OPTS="-XX:AOTCache=$TRAIN/r7.aot $JVM_FLAGS" \
    "$HERE/run.sh" --mode jvm-local --jar "$JAR" --out "$OUT" "${PINS[@]}" "${PROFILE[@]}" 9>&- &
else
  # The image's own entrypoint flags and AOT cache; JVM_OPTS only adds the collector choice.
  R7_BENCH_IMAGE="$IMAGE_REF" JVM_OPTS="$JVM_GC" \
    "$HERE/run.sh" --mode docker --out "$OUT" "${PINS[@]}" "${PROFILE[@]}" 9>&- &
fi
RUN_PID=$!
echo "$RUN_PID" > "$STATE_DIR/run.pid"
if (( ${ISOLATED:-0} )); then
  LATE="$(mktemp)"
  watch_unconfined "$RUN_PID" "$LATE" 9>&- &
  WATCH_PID=$!
fi
trap 'kill -TERM "$RUN_PID" 2>/dev/null; wait "$RUN_PID" 2>/dev/null; exit 130' INT TERM
wait "$RUN_PID"
trap 'exit 130' INT TERM
if (( ${ISOLATED:-0} )); then
  wait "$WATCH_PID"
  while IFS= read -r u; do
    UNTUNED+=("core isolation: processes in the top-level cgroup $u during the run, which is not confined")
  done < <(sort -u "$LATE")
  rm -f "$LATE"
fi

{
  if (( LOCAL )); then
    echo "bench=bench.sh${QUICK_FLAG} --local --jdk $JDK --gc $GC"
    echo "gateway=local build of $SHA"
    echo "run_jdk=$("$RUN_JDK/bin/java" -version 2>&1 | sed -n 2p)"
    echo "build_jdk=$("$CACHE/jdk-27/bin/java" -version 2>&1 | sed -n 2p)"
  else
    echo "bench=bench.sh${QUICK_FLAG} --image $IMAGE --gc $GC"
    echo "gateway=$IMAGE_REF"
    echo "image_jdk=$IMAGE_JAVA"
  fi
  echo "wrk_commit=$WRK_COMMIT wrk2_commit=$WRK2_COMMIT"
  echo "git=$SHA dirty=$([[ -n "$DIRTY" ]] && echo yes || echo no)"
  echo "layout: os=$OS_CPUS backend=$BACKEND_CPUS gateway=$GATEWAY_CPUS load=$LOAD_CPUS"
  echo "tuning=${TUNING[*]}"
  echo "not_tuned=${UNTUNED[*]:-none}"
  host_report
} > "$OUT/host.txt"
cp "$STATE" "$OUT/host-before.txt"
(( LOCAL )) && cp "$TRAIN/training.log" "$OUT/aot-training.log"

# ----------------------------------------------------------------- verdict

REASONS=()
(( QUICK )) && REASONS+=("--quick profile")
(( ${NO_DIGEST:-0} )) && REASONS+=("$IMAGE has no registry digest (a local image, or none matched the reference), so nobody else can pull the same one")
[[ -n "${R7_BENCH_BACKEND_IMAGE:-}" && "$R7_BENCH_BACKEND_IMAGE" != *@sha256:* ]] \
  && REASONS+=("R7_BENCH_BACKEND_IMAGE is not pinned by digest")
[[ -n "$DIRTY" ]] && REASONS+=("uncommitted changes in the working tree")
for u in "${UNTUNED[@]}"; do REASONS+=("not tuned: $u"); done
# A validator that fails must block the verdict, not silently report no problems.
if PROBLEMS="$(python3 -B -c "import sys; sys.path.insert(0, sys.argv[1]); import parse
for p in parse.verdict_problems(sys.argv[2], 3, float(sys.argv[3])): print(p)" "$HERE/lib" "$OUT" "$MAX_SPREAD")"; then
  while IFS= read -r line; do [[ -n "$line" ]] && REASONS+=("$line"); done <<< "$PROBLEMS"
else
  REASONS+=("result validation failed")
fi

{
  if (( ${#REASONS[@]} == 0 )); then
    echo "**PUBLISHABLE** — $GATEWAY, $(lscpu | sed -n 's/^Model name:[[:space:]]*//p' | head -n1)"
  else
    echo "**NOT PUBLISHABLE**"
    for r in "${REASONS[@]}"; do echo "- $r"; done
  fi
  echo
  cat "$OUT/REPORT.md"
} > "$OUT/REPORT.md.tmp" && mv "$OUT/REPORT.md.tmp" "$OUT/REPORT.md"

TARBALL="$HERE/results/bench-$ID-$TS.tar.gz"
tar -czf "$TARBALL" -C "$HERE/results" "$TS"
[[ -n "${SUDO_USER:-}" ]] && chown -R "$SUDO_USER:" "$OUT" "$TARBALL"
(( LOCAL )) && as_user rm -rf "$TRAIN"

echo
head -n "$(( ${#REASONS[@]} + 1 ))" "$OUT/REPORT.md"
ok "report:  $OUT/REPORT.md"
ok "archive: $TARBALL"
