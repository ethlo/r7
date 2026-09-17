# r7 gateway benchmarks

A reproducible suite for answering one question honestly: **what does it cost to
put r7 in the request path?**

Every number this suite prints is a delta against a baseline run in which the
load generator talks straight to the backend. An absolute "r7 does N req/s"
figure is close to meaningless — it describes the hardware, the backend and the
load generator as much as the gateway.

## Quick start

```bash
# build the gateway first
mvn -q -DskipTests install

cd benchmark
./run.sh --quick          # ~3 minutes, sanity check
./run.sh                  # full suite, ~25 minutes
```

Results land in `benchmark/results/<timestamp>/`:

```
environment.txt   host, JVM, wrk versions, git SHA — quote this with any number
REPORT.md         the comparison table
summary.tsv       same, machine-readable
raw/              unmodified wrk/wrk2 output, including warmups
*.json            parsed per-run stats
gateway-*.log     gateway stdout/stderr per journal level
```

## What it measures

| Scenario | What it isolates |
| --- | --- |
| `baseline` | The floor. wrk → nginx directly. Everything else is reported as a delta against this. |
| `passthrough` | r7 in the path, no filters, journaling off. The irreducible cost of proxying. |
| `filtered` | Same, through `AddCorrelationId` + request/response header rewrites. The cost of the filter chain. |
| `journal` | The same workload at journal level `NONE` → `METADATA` → `HEADERS` → `FULL`, fresh JVM each time. The real cost of the mmap journal. |

Each scenario runs across three workloads:

| Workload | Shape |
| --- | --- |
| `browser` | GET, 16 Chrome headers, keep-alive, tiny response. The common case. |
| `headers` | GET, 36 headers (CDN + WAF + mesh + B3/W3C tracing). Targets header storage and the `HEADERS` journal encoder. |
| `post` | POST, ~1KB JSON body. Targets body teeing and `BODY` markers — a path GET benchmarks never touch. Size overridable via `BENCH_BODY_BYTES`. |

And across two tools, because they answer different questions:

- **wrk** — open the taps, find the saturation throughput. Its latency figures
  suffer from coordinated omission and understate the tail. Use it for req/s.
- **wrk2** — hold a fixed rate (`--rate`, default 20000) and record latency
  against a proper HdrHistogram. **These are the latency numbers to quote**,
  especially p99 and p99.9.

## The backend

`backend/nginx.conf`: a single `return 200 "OK"`, `access_log off`, `reuseport`,
and effectively unlimited keep-alive. No disk I/O, no filesystem lookups, no
application runtime.

This is deliberately the cheapest credible HTTP responder available. Anything
richer (an echo server, a Node or Python app) becomes the bottleneck long before
r7 does, and the benchmark then measures the backend. If the baseline row's
throughput is not comfortably above every gateway row, the backend is limiting
you and the run should be discarded.

## Methodology notes

**Warmup is not optional.** A cold JVM is 3–10× slower than a warm one, and C2
needs tens of thousands of iterations to settle. Every run is preceded by a
discarded warmup (default 20s) whose output is kept in `raw/*.warmup.txt` so you
can confirm the JIT actually settled.

**Fresh JVM per journal level.** The journal matrix restarts the gateway between
levels rather than hot-reloading, so no profile pollution or already-faulted
mmap pages carry across.

**Validity gates.** The parser marks a run `INVALID` and refuses to let it be
quoted cleanly if it saw >0.1% non-2xx responses, any socket errors, or any
timeouts. This catches the classic failure where a rate limiter or an auth
filter turns the run into a 429 benchmark and the throughput looks great.

**Config is deliberately bare.** `config/routes.yaml.tmpl` has no
`global_filters`. Metrics, CORS and size limits are real costs, but they are
*feature* costs — measure them by adding a scenario, not by baking them into the
number you call "gateway overhead".

**Co-located load generator.** `run.sh` warns about this, and it matters: wrk and
the gateway compete for the same cores. Results are valid for comparing runs
against each other on the same host. For absolute capacity figures, run the load
generator on a separate machine over a link that is not itself the bottleneck.

## Host tuning

`run.sh` checks these and warns; it does not change them for you.

```bash
ulimit -n 65535
sudo sysctl -w net.ipv4.ip_local_port_range="1024 65535"
sudo sysctl -w net.ipv4.tcp_tw_reuse=1
sudo cpupower frequency-set -g performance    # or your distro's equivalent
```

Without these you will hit a wall well below the gateway's actual limit and
conclude the wrong thing.

## Modes

```bash
./run.sh --mode jvm-local     # default: java -jar on the host
./run.sh --mode docker        # the shipped ghcr.io/ethlo/r7-jvm image
```

`jvm-local` gives the cleanest numbers — no container, no cgroup accounting.
`docker` measures what users actually deploy. Both use host networking for the
backend, so neither pays Docker's NAT cost.

To reproduce the memory-pressure study in `docs/benchmarks.md`:

```bash
R7_BENCH_MEM=200M ./run.sh --mode docker --scenario passthrough --tool wrk2
```

## Common options

```bash
./run.sh --scenario journal                  # just the journaling matrix
./run.sh --workload post --tool wrk2         # just POST latency
./run.sh --rate 50000 --duration 60s         # push the fixed-rate test harder
./run.sh --connections 1000 --threads 16     # connection-heavy
./run.sh --keep-running                      # leave the stack up to poke at
./run.sh --help
```

## Installing wrk and wrk2

Neither is packaged on most distros. Both build in under a minute:

```bash
git clone https://github.com/wg/wrk       && make -C wrk       -j && sudo install wrk/wrk   /usr/local/bin/wrk
git clone https://github.com/giltene/wrk2 && make -C wrk2      -j && sudo install wrk2/wrk  /usr/local/bin/wrk2
```

Note that wrk2's binary is also called `wrk`; install it as `wrk2` as shown, or
`run.sh` will silently run the wrong tool.

## Reporting results

Paste `REPORT.md` together with `environment.txt`. A latency figure without the
host, JVM version, connection count and request rate that produced it is not a
result, it is a claim.
