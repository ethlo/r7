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
  (header filters), `journal` at `METADATA`, `HEADERS` and `FULL`, and a `sweep` of fixed offered
  rates on the API GET that shows p99 against load. The scenarios run over three requests a
  gateway sees, each answered with a response of matching shape: a browser page GET (20 headers,
  8 KB of HTML back), an API GET (10 headers, 2 KB of JSON back) and an API POST (a 1 KB JSON body,
  a 201 with a small JSON body back).
- **Repeats.** One JVM per configuration, warmed for 30 s and for 15 s more on each workload
  switch. wrk runs three times for 15 s, and the spread between those runs is printed next to every
  throughput figure. wrk2 runs once for 30 s.
- **Tools.** wrk finds saturation throughput. wrk2 holds a fixed rate and records latency in an
  HdrHistogram, so its p99 and p99.9 are free of coordinated omission.
- **Verdict.** Each report opens with PUBLISHABLE or NOT PUBLISHABLE: a pulled image (or a
  `--local` build), clean tree, every tuning step applied, every planned run present and valid.
  The spread is not part of the verdict; each row carries its own.

The full methodology is in [`benchmark/README.md`](https://github.com/ethlo/r7/blob/main/benchmark/README.md).

## Hardware

One run of `sudo benchmark/bench.sh` on a 12th Gen Intel Core i5-12500 (six performance cores,
SMT and turbo off, `performance` governor, 62 GiB RAM, Linux 6.8.0-142). The gateway image was
`ghcr.io/ethlo/r7-gateway@sha256:97f0d156cfa4a722cb9bb09386f636659014f267e7679691b79c1faeb4bc6aaf` (Temurin 27+35, the JVM's default collector),
wrk `a211dd5` and wrk2 `44a94c1`.

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
latency. All runs use 200 connections. 20,000 req/s is 53% of what the passthrough gateway
saturates at on two cores for the API requests and 71% for the browser page. With the `HEADERS`
journal the browser page saturates at 21,800 req/s, so that rate is 92% of capacity; with `FULL` it
saturates at 13,700 req/s, below the rate, and that cell is left out. Milliseconds: p50 / p99 /
p99.9.

| Scenario | Browser page GET | API GET | API POST |
| --- | --- | --- | --- |
| No gateway (nginx direct) | 1.1 / 2.2 / 2.6 | 1.2 / 2.7 / 3.2 | 1.1 / 2.3 / 2.5 |
| r7 passthrough | 2.2 / 10.8 / 19.9 | 2.5 / 6.3 / 9.0 | 2.4 / 5.0 / 8.8 |
| r7 with three header filters | 3.1 / 7.1 / 9.2 | 2.6 / 6.2 / 7.7 | 2.0 / 4.5 / 6.3 |
| Journal `METADATA` | 3.3 / 7.1 / 14.8 | 2.5 / 5.2 / 7.0 | 2.4 / 6.0 / 7.7 |
| Journal `HEADERS` | 6.8 / 20.9 / 43.6 | 2.6 / 5.6 / 7.2 | 2.6 / 5.8 / 9.7 |
| Journal `FULL` | above capacity | 3.4 / 9.7 / 15.6 | 3.3 / 10.6 / 30.9 |

On the API requests r7 adds 2.7 to 3.6 ms at p99 over nginx, and the header filters, `METADATA` and
`HEADERS` stay within 1.1 ms of passthrough at p99. `FULL` records every body and adds 3.4 and
5.6 ms at p99. The browser page returns 8 KB per request and runs at 71% of capacity at this rate,
so its tail is longer. Its passthrough p99 of 10.8 ms is above the filtered run's 7.1 ms: there, the
difference between two single wrk2 runs is larger than what the filters cost, so read its rows as a
range rather than a ranking.

### Saturation throughput

wrk opens every connection and measures the most the gateway sustains. Filters and journaling are
compared with the passthrough run, which has the same topology and the same contention. Its latency
figures understate the tail, so use the table above for latency. Each value is followed by the
spread of its three repeats.

| Scenario | Browser page GET | API GET | API POST |
| --- | --- | --- | --- |
| r7 passthrough (requests per second) | 28,300 (±5.7%) | 37,800 (±6.2%) | 37,600 (±3.1%) |
| r7 with three header filters | −7% (±4.8%) | −2% (±3.0%) | −3% (±1.8%) |
| Journal `METADATA` | −5% (±7.2%) | −5% (±1.3%) | −6% (±2.9%) |
| Journal `HEADERS` | −23% (±5.7%) | −18% (±2.5%) | −18% (±0.3%) |
| Journal `FULL` | −52% (±4.4%) | −31% (±1.9%) | −28% (±3.4%) |

The filters cost up to 7% and `METADATA` up to 6%, both within or close to the spread. `HEADERS`
costs 18% to 23%. `FULL` costs 28% to 31% on the API requests and 52% on the browser page, which
records 8 KB of HTML with every exchange.

### Where the tail breaks away

Fixed-rate sweeps on the API GET, at 50, 75, 90 and 100% of the saturation throughput the run
measured, show how p99 grows with offered load. Milliseconds:

| Journal off: offered (req/s) | p99 | Journal `FULL`: offered (req/s) | p99 |
| --- | --- | --- | --- |
| 18,900 | 3.0 | 13,000 | 3.3 |
| 28,400 | 10.1 | 19,500 | 8.9 |
| 34,000 | 33.0 | 23,400 | 165.3 |
| 37,800 | 371.7 | 26,000 | 1,900 |

With the journal off, p99 stays under 11 ms up to 28,400 req/s, reaches 33 ms at 34,000 and breaks away at 37,800. With `FULL`
it stays under 9 ms to 19,500 req/s and breaks away by 23,400.

## Reproduce

On an x86_64 Linux host with systemd, Docker and `build-essential libssl-dev`:

```bash
git clone https://github.com/ethlo/r7 && cd r7
sudo benchmark/bench.sh
```

The report and a tarball of everything it measured, including `host.txt` with the image
digest, CPU, kernel and tuning, land in `benchmark/results/`. The tuning is restored when the
script exits.
