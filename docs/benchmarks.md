# Benchmarks

The question is what putting r7 in the request path costs. The suite reports two comparisons.
Against the no-gateway baseline it shows the total cost of the proxy hop, an upper bound while
the load generator shares the host. Against the passthrough run (r7 in the path, no filters, no
journal) it shows what a filter or a journal level adds: same topology, same contention, so the
noise cancels. Filter and journal costs are quoted against passthrough. Throughput and tail
latency are reported together.

## Method

- **One command.** `sudo benchmark/bench.sh` produces the numbers in under an hour. It measures
  the published image `ghcr.io/ethlo/r7-gateway`, resolved to its digest, with the image's own JVM
  flags and AOT cache.
- **Pinned toolchain.** wrk and wrk2 are built from pinned commits, and the nginx backend image
  is pinned by digest.
- **Tuned host.** SMT and turbo off, the `performance` governor, network sysctls, and every
  other process confined to housekeeping cores through systemd.
- **Separate cores.** nginx, the gateway and the load generator each run on cores of their own.
- **Scenarios.** `baseline` (no gateway), `passthrough` (r7, no filters, no journal), `filtered`
  (header filters), `journal` at `METADATA`, `HEADERS` and `FULL`, `peers` (an nginx reverse
  proxy and Spring Cloud Gateway in r7's place), and a `sweep` of fixed offered rates on the API
  GET that shows p99 against load. The scenarios run over three requests a
  gateway sees, each answered with a response of matching shape: a browser page GET (20 headers,
  8 KB of HTML back), an API GET (10 headers, 2 KB of JSON back) and an API POST (a 1 KB JSON body,
  a 201 with a small JSON body back).
- **Repeats.** One JVM per configuration, warmed for 30 s and for 15 s more on each workload
  switch. wrk runs three times for 15 s, and the spread between those runs is printed next to every
  throughput figure. wrk2 runs once for 30 s.
- **Tools.** wrk finds saturation throughput. wrk2 holds a fixed rate and records latency in an
  HdrHistogram, so its p99 and p99.9 are free of coordinated omission.
- **Verdict.** Each report opens with PUBLISHABLE or NOT PUBLISHABLE: a pulled image (or a
  `--local` build), clean tree, every tuning step applied, every planned run present and valid,
  and the backend faster than every r7 row by 10%. The spread is not part of the verdict; each row
  carries its own. A peer that reaches the backend's limit, and a fixed-rate run above what its
  configuration saturates at, are marked in the report as results rather than failing the run.

The full methodology is in [`benchmark/README.md`](https://github.com/ethlo/r7/blob/main/benchmark/README.md).

## Hardware

One run of `sudo benchmark/bench.sh` on a 12th Gen Intel Core i5-12500 (six performance cores,
SMT and turbo off, `performance` governor, 62 GiB RAM, Linux 6.8.0-142). The gateway image was
`ghcr.io/ethlo/r7-gateway@sha256:292e25edcc827cda7cfc1fce81cc2e86935539e21078794835159524d67e1a19`
(Temurin 27+35, G1, the JVM's default collector at two CPUs), from r7 `2d06d07`, with wrk `a211dd5`
and wrk2 `44a94c1`.

The six cores are split four ways: nginx on one, **the gateway on two**, the load generator on two,
the OS on one. Everything the gateway does, including the journal, shares those two cores. The
absolute throughput below reflects that allocation: read it per core, and compare scenarios with
each other.

## Results

These figures give an indication of the cost, not a measurement to the decimal. They come from one
run, and every throughput figure carries the spread of its three repeats. A difference smaller than
that spread is not a finding.

### Latency at a fixed 20,000 req/s

wrk2 holds the rate and corrects for coordinated omission, so these are the figures to read for
latency. All runs use 200 connections. 20,000 req/s is 46% of what the passthrough gateway
saturates at on two cores for the API requests and 62% for the browser page. With the `HEADERS`
journal the browser page saturates at 24,000 req/s, so that rate is 83% of capacity; with `FULL` it
saturates at 14,900 req/s, below the rate, and that cell is left out. Milliseconds: p50 / p99 /
p99.9.

| Scenario | Browser page GET | API GET | API POST |
| --- | --- | --- | --- |
| No gateway (nginx direct) | 1.0 / 2.2 / 2.6 | 1.0 / 2.1 / 2.4 | 1.1 / 2.3 / 2.6 |
| r7 passthrough | 3.1 / 7.8 / 17.5 | 2.1 / 4.4 / 5.6 | 2.0 / 4.3 / 5.4 |
| r7 with three header filters | 2.6 / 6.6 / 8.7 | 2.0 / 4.1 / 5.1 | 2.0 / 4.5 / 5.5 |
| Journal `METADATA` | 2.6 / 5.5 / 10.3 | 2.1 / 4.3 / 5.7 | 2.1 / 4.4 / 5.7 |
| Journal `HEADERS` | 3.4 / 8.2 / 17.0 | 2.1 / 5.0 / 13.5 | 2.2 / 4.8 / 7.6 |
| Journal `FULL` | above capacity | 2.6 / 5.8 / 9.3 | 2.6 / 5.9 / 17.0 |

On the API requests r7 adds 2.0 to 2.3 ms at p99 over nginx. The header filters and `METADATA` stay
within 0.3 ms of passthrough at p99, and `HEADERS` within 0.6 ms. `FULL` records every body and adds
1.4 and 1.6 ms at p99. The browser page returns 8 KB per request and runs at 62% of capacity at this
rate, so its tail is longer. Its passthrough p99 of 7.8 ms is above the filtered run's 6.6 ms and
`METADATA`'s 5.5 ms: there, the difference between two single wrk2 runs is larger than what the
filters or the journal cost, so read its rows as a range rather than a ranking.

### Saturation throughput

wrk opens every connection and measures the most the gateway sustains. Filters and journaling are
compared with the passthrough run, which has the same topology and the same contention. Its latency
figures understate the tail, so use the table above for latency. Each value is followed by the
spread of its three repeats.

| Scenario | Browser page GET | API GET | API POST |
| --- | --- | --- | --- |
| r7 passthrough (requests per second) | 32,100 (±3.7%) | 43,600 (±5.1%) | 44,900 (±2.7%) |
| r7 with three header filters | −11% (±8.7%) | −1% (±3.2%) | −5% (±3.8%) |
| Journal `METADATA` | −4% (±4.5%) | −5% (±0.6%) | −14% (±0.6%) |
| Journal `HEADERS` | −25% (±5.4%) | −21% (±5.9%) | −24% (±0.8%) |
| Journal `FULL` | −54% (±2.5%) | −34% (±0.6%) | −34% (±0.6%) |

The filters cost up to 5% on the API requests and 11% on the browser page, within its spread of
8.7%. `METADATA` costs 4% and 5% on the GETs and 14% on the POST. `HEADERS` costs 21% to 25%.
`FULL` costs 34% on the API requests and 54% on the browser page, which records 8 KB of HTML with
every exchange.

### Against other gateways

The same run puts two other gateways in r7's place: same port, same two cores, same 990 MB
memory limit, same backend and workloads. One is nginx as a reverse proxy with upstream keep-alive
and no access log. The other is [cloud-gateway](https://github.com/ethlo/cloud-gateway), Spring
Cloud Gateway on WebFlux, with its own capture off, on the same collector as r7. Their
configurations are in
[`benchmark/peers/`](https://github.com/ethlo/r7/tree/main/benchmark/peers). Saturation in requests
per second, each value with the spread of its three repeats:

| Gateway | Browser page GET | API GET | API POST |
| --- | --- | --- | --- |
| nginx reverse proxy | 85,700 (±0.9%), the backend's limit | 60,300 (±8.9%), the backend's limit | 107,000 (±5.1%), the backend's limit |
| r7 passthrough | 32,100 (±3.7%) | 43,600 (±5.1%) | 44,900 (±2.7%) |
| cloud-gateway | 13,950 (±8.1%) | 18,600 (±7.0%) | 15,400 (±0.6%) |

nginx proxies as fast as the backend answers on its one core (85,000, 62,900 and 101,300 req/s
direct), so its own ceiling is higher than these figures. r7 sustains 2.3 to 2.9 times
cloud-gateway's throughput on the same cores. At a fixed 20,000 req/s, milliseconds, p50 / p99 /
p99.9:

| Gateway | Browser page GET | API GET | API POST |
| --- | --- | --- | --- |
| nginx reverse proxy | 1.3 / 2.6 / 2.9 | 1.2 / 2.4 / 2.8 | 1.4 / 2.4 / 2.6 |
| r7 passthrough | 3.1 / 7.8 / 17.5 | 2.1 / 4.4 / 5.6 | 2.0 / 4.3 / 5.4 |
| cloud-gateway | above capacity | above capacity | above capacity |

On the API requests r7 adds 1.9 and 2.0 ms at p99 over the nginx proxy. cloud-gateway saturates
below 20,000 req/s on all three requests, so at that rate its latency is a queue of seconds rather
than its latency at that load.

### Where the tail breaks away

Fixed-rate sweeps on the API GET, at 50, 75, 90 and 100% of the saturation throughput the run
measured, show how p99 grows with offered load. Milliseconds:

| Journal off: offered (req/s) | p99 | Journal `FULL`: offered (req/s) | p99 |
| --- | --- | --- | --- |
| 21,800 | 2.9 | 14,300 | 3.0 |
| 32,700 | 6.5 | 21,500 | 4.9 |
| 39,300 | 55.8 | 25,800 | 72.4 |
| 43,600 | 221.2 | 28,700 | 864 |

With the journal off, p99 stays under 7 ms up to 32,700 req/s, reaches 56 ms at 39,300 and breaks
away at 43,600. With `FULL` it stays under 5 ms to 21,500 req/s and breaks away by 25,800.

## Reproduce

On an x86_64 Linux host with systemd, Docker and `build-essential libssl-dev`:

```bash
git clone https://github.com/ethlo/r7 && cd r7
sudo benchmark/bench.sh
```

The report and a tarball of everything it measured, including `host.txt` with the image
digest, CPU, kernel and tuning, land in `benchmark/results/`. The tuning is restored when the
script exits.
