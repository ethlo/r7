#!/usr/bin/env bash
#
# One command for a benchmark run that can be repeated and quoted.
#
#   sudo benchmark/bench.sh             # full run: pinned toolchain, tuned host, fixed profile
#   sudo benchmark/bench.sh --quick     # same pipeline with short runs, as a sanity check
#   benchmark/bench.sh --check          # no changes: show the core layout and host state
#   sudo benchmark/bench.sh --restore   # undo host tuning left behind by a killed run
#
# It downloads and verifies a pinned JDK, wrk and wrk2, builds r7 and trains its AOT cache
# the way the image does, tunes the host for the run (performance governor, turbo and SMT
# off, every other process confined to housekeeping cores), pins nginx, the gateway and the
# load generator to separate cores, runs run.sh with a fixed profile and puts the host back.
# Every host change is a runtime one, so a reboot also undoes it.
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

JDK=25
GC="default"
QUICK=0
ACTION="run"

c_red=$'\033[31m'; c_grn=$'\033[32m'; c_yel=$'\033[33m'; c_dim=$'\033[2m'; c_off=$'\033[0m'
log()  { printf '%s[bench]%s %s\n' "$c_dim" "$c_off" "$*" >&2; }
ok()   { printf '%s[bench]%s %s\n' "$c_grn" "$c_off" "$*" >&2; }
warn() { printf '%s[bench]%s %s\n' "$c_yel" "$c_off" "$*" >&2; }
die()  { printf '%s[bench]%s %s\n' "$c_red" "$c_off" "$*" >&2; exit 1; }

usage() {
  sed -n '3,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  cat <<'EOF'

Options:
  --quick          short runs, browser workload only; never publishable
  --jdk 25|27      JDK the gateway runs on (default: 25, what the image ships)
  --gc default|zgc collector (default: the JVM's choice, as in the image)
  --check          print the core layout and current host state, change nothing
  --restore        restore host settings saved by an interrupted run
  -h, --help       this
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --quick)   QUICK=1; shift ;;
    --jdk)     JDK="$2"; shift 2 ;;
    --gc)      GC="$2"; shift 2 ;;
    --check)   ACTION="check"; shift ;;
    --restore) ACTION="restore"; shift ;;
    -h|--help) usage; exit 0 ;;
    *)         die "unknown option: $1 (try --help)" ;;
  esac
done

[[ "$JDK" == "25" || "$JDK" == "27" ]] || die "--jdk must be 25 or 27"
[[ "$GC" == "default" || "$GC" == "zgc" ]] || die "--gc must be default or zgc"

# ----------------------------------------------------------------- host state

# Each line of the state file is "kind|target|original value"; restore applies them in
# reverse, so SMT comes back last, after the governors of the CPUs that stayed online.
save() { printf '%s|%s|%s\n' "$1" "$2" "$3" >> "$STATE"; }

# A setting that fails to restore stays in the state file, so --restore can retry it.
restore_host() {
  [[ -f "$STATE" ]] || return 0
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
  for unit in system.slice user.slice init.scope machine.slice; do
    [[ "$(systemctl show -p ActiveState --value "$unit" 2>/dev/null)" == "active" ]] || continue
    old="$(systemctl show -p AllowedCPUs --value "$unit")"
    save cpuset "$unit" "$old"
    systemctl set-property --runtime "$unit" "AllowedCPUs=$os_cpus" \
      || { UNTUNED+=("core isolation: $unit refused AllowedCPUs"); return 0; }
  done
  TUNING+=("isolation=systemd(os=$os_cpus)")
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

fetch_jdk() {
  local url="$1" sha="$2" dir="$3"
  [[ -x "$dir/bin/java" ]] && return 0
  local tgz="$CACHE/dl/$(basename "$url")"
  log "downloading $(basename "$url")"
  as_user mkdir -p "$CACHE/dl" "$dir"
  as_user curl -fsSL -o "$tgz" "$url"
  echo "$sha  $tgz" | sha256sum -c --quiet - || { rm -f "$tgz"; die "checksum mismatch for $tgz"; }
  as_user tar -xzf "$tgz" -C "$dir" --strip-components=1
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
  fetch_jdk "$JDK27_URL" "$JDK27_SHA256" "$CACHE/jdk-27"
  [[ "$JDK" == "25" ]] && fetch_jdk "$JDK25_URL" "$JDK25_SHA256" "$CACHE/jdk-25"
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
lock_host
[[ -f "$STATE" ]] && die "settings from an interrupted run are still saved; run --restore first"

toolchain
export PATH="$CACHE/bin:$PATH"
RUN_JDK="$CACHE/jdk-$JDK"

log "building r7 with JDK 27"
( cd "$REPO" && as_user env JAVA_HOME="$CACHE/jdk-27" \
    ./mvnw -q -DskipTests -Dmaven.javadoc.skip=true -Dmaven.source.skip=true package -pl r7-helidon -am )
JAR="$(ls "$REPO"/r7-helidon/target/r7-helidon-*[0-9T].jar | head -n1)"
[[ -f "$JAR" ]] || die "no gateway jar after the build"

# Untracked files count: Maven would build an untracked source too. Ignored ones (target/,
# .cache/, results/) do not.
DIRTY="$(git -C "$REPO" status --porcelain --untracked-files=normal)"
SHA="$(git -C "$REPO" rev-parse --short HEAD)"

# The image's JVM flags, and its AOT cache trained the same way Dockerfile.jvm does. The
# cache only fits the JDK, jars and flags it was made with, so it is made here, per run.
JVM_FLAGS="--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -Djava.security.egd=file:/dev/./urandom"
[[ "$GC" == "zgc" ]] && JVM_FLAGS="-XX:+UseZGC $JVM_FLAGS"
TRAIN="$(as_user mktemp -d)"
log "training the AOT cache on JDK $JDK"
( cd "$TRAIN" && as_user env R7_ROUTES_CONFIG="$REPO/docker/aot-training/routes.yaml" \
    R7_JOURNAL_DIR="$TRAIN/journals" \
    "$RUN_JDK/bin/java" -XX:AOTCacheOutput="$TRAIN/r7.aot" $JVM_FLAGS -Dr7.aot.training=true -jar "$JAR" ) \
  > "$TRAIN/training.log" 2>&1 || die "AOT training failed; see $TRAIN/training.log"
[[ -f "$TRAIN/r7.aot" ]] || die "AOT training wrote no cache; see $TRAIN/training.log"

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
(( QUICK )) && PROFILE=(--quick) && QUICK_FLAG=" --quick"

PATH="$RUN_JDK/bin:$PATH" JVM_OPTS="-XX:AOTCache=$TRAIN/r7.aot $JVM_FLAGS" \
  "$HERE/run.sh" --mode jvm-local --jar "$JAR" --out "$OUT" \
    --backend-cpus "$BACKEND_CPUS" --gateway-cpus "$GATEWAY_CPUS" --load-cpus "$LOAD_CPUS" \
    "${PROFILE[@]}"

{
  echo "bench=bench.sh${QUICK_FLAG} --jdk $JDK --gc $GC"
  echo "run_jdk=$("$RUN_JDK/bin/java" -version 2>&1 | sed -n 2p)"
  echo "build_jdk=$("$CACHE/jdk-27/bin/java" -version 2>&1 | sed -n 2p)"
  echo "wrk_commit=$WRK_COMMIT wrk2_commit=$WRK2_COMMIT"
  echo "git=$SHA dirty=$([[ -n "$DIRTY" ]] && echo yes || echo no)"
  echo "layout: os=$OS_CPUS backend=$BACKEND_CPUS gateway=$GATEWAY_CPUS load=$LOAD_CPUS"
  echo "tuning=${TUNING[*]}"
  echo "not_tuned=${UNTUNED[*]:-none}"
  host_report
} > "$OUT/host.txt"
cp "$STATE" "$OUT/host-before.txt"
cp "$TRAIN/training.log" "$OUT/aot-training.log"

# ----------------------------------------------------------------- verdict

REASONS=()
(( QUICK )) && REASONS+=("--quick profile")
[[ -n "$DIRTY" ]] && REASONS+=("uncommitted changes in the working tree")
for u in "${UNTUNED[@]}"; do REASONS+=("not tuned: $u"); done
while IFS= read -r line; do [[ -n "$line" ]] && REASONS+=("$line"); done \
  < <(python3 -B -c "import sys; sys.path.insert(0, sys.argv[1]); import parse
for p in parse.verdict_problems(sys.argv[2], 3, float(sys.argv[3])): print(p)" "$HERE/lib" "$OUT" "$MAX_SPREAD")

{
  if (( ${#REASONS[@]} == 0 )); then
    echo "**PUBLISHABLE** — r7 $SHA, JDK $JDK, $(lscpu | sed -n 's/^Model name:[[:space:]]*//p' | head -n1)"
  else
    echo "**NOT PUBLISHABLE**"
    for r in "${REASONS[@]}"; do echo "- $r"; done
  fi
  echo
  cat "$OUT/REPORT.md"
} > "$OUT/REPORT.md.tmp" && mv "$OUT/REPORT.md.tmp" "$OUT/REPORT.md"

TARBALL="$HERE/results/bench-$SHA-$TS.tar.gz"
tar -czf "$TARBALL" -C "$HERE/results" "$TS"
[[ -n "${SUDO_USER:-}" ]] && chown -R "$SUDO_USER:" "$OUT" "$TARBALL"
as_user rm -rf "$TRAIN"

echo
head -n "$(( ${#REASONS[@]} + 1 ))" "$OUT/REPORT.md"
ok "report:  $OUT/REPORT.md"
ok "archive: $TARBALL"
