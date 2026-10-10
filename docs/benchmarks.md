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
  rates on the browser workload that shows p99 against load. The scenarios run over a
  browser-shaped GET, a 36-header GET and a 1 KB JSON POST.
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
`ghcr.io/ethlo/r7-gateway@sha256:b5a7f7218bde53ce011de0f5ce6e870a0776212d2dfe04ded350bbc59d0f2877` (Temurin 27+35, the JVM's default collector),
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
latency. All runs use 200 connections. 20,000 req/s is 46% to 61% of what the gateway saturates at
on two cores, depending on the workload. Milliseconds: p50 / p99 / p99.9.

| Scenario | Browser GET | 36-header GET | 1 KB POST |
| --- | --- | --- | --- |
| No gateway (nginx direct) | 1.0 / 2.0 / 2.2 | 1.3 / 2.7 / 3.2 | 1.0 / 1.9 / 2.2 |
| r7 passthrough | 2.6 / 5.5 / 6.7 | 3.4 / 7.5 / 9.9 | 2.1 / 4.4 / 5.7 |
| r7 with three header filters | 2.4 / 4.7 / 6.1 | 2.4 / 5.4 / 7.8 | 2.1 / 4.6 / 5.7 |
| Journal `METADATA` | 1.9 / 4.4 / 7.6 | 3.7 / 8.5 / 11.2 | 2.1 / 4.5 / 6.2 |
| Journal `HEADERS` | 2.4 / 5.3 / 7.8 | 6.1 / 19.0 / 30.3 | 2.8 / 6.3 / 8.2 |
| Journal `FULL` | 2.2 / 5.5 / 18.0 | 5.3 / 33.4 / 56.0 | 2.5 / 5.4 / 9.4 |

r7 adds 2.4 to 4.8 ms at p99 over nginx on this host. The header filters and the `METADATA` journal
land within about 2 ms of passthrough in either direction, which is the run to run noise. At `HEADERS` and `FULL` the 36-header request
pays for recording every header: its p99 reaches 19 and 33 ms.

### Saturation throughput

wrk opens every connection and measures the most the gateway sustains. Filters and journaling are
compared with the passthrough run, which has the same topology and the same contention. Its latency
figures understate the tail, so use the table above for latency. Each value is followed by the
spread of its three repeats.

| Scenario | Browser GET | 36-header GET | 1 KB POST |
| --- | --- | --- | --- |
| r7 passthrough (requests per second) | 36,800 (±8.7%) | 32,600 (±5.5%) | 43,400 (±0.7%) |
| r7 with three header filters | +6% (±5.7%) | +2% (±1.7%) | −6% (±6.6%) |
| Journal `METADATA` | +2% (±4.2%) | −3% (±0.4%) | −7% (±3.7%) |
| Journal `HEADERS` | −13% (±2.3%) | −35% (±1.9%) | −14% (±2.9%) |
| Journal `FULL` | −16% (±5.6%) | −38% (±0.5%) | −29% (±13.5%) |

The filters and `METADATA` sit inside the noise of the passthrough baseline: no cost is measurable.
`HEADERS` and `FULL` cost 13% to 38%, and the 36-header request is the most expensive because it
records the most.

### Where the tail breaks away

Fixed-rate sweeps on the browser workload, at 50, 75, 90 and 100% of the saturation throughput the
run measured, show how p99 grows with offered load. Milliseconds:

| Journal off: offered (req/s) | p99 | Journal `FULL`: offered (req/s) | p99 |
| --- | --- | --- | --- |
| 18,400 | 2.8 | 15,500 | 3.6 |
| 27,600 | 5.4 | 23,200 | 11.8 |
| 33,100 | 48.7 | 27,800 | 325.9 |
| 36,800 | 76.4 | 30,900 | 1,820 |

With the journal off, p99 stays under 6 ms up to 27,600 req/s and breaks away by 33,100. With `FULL`
it stays under 12 ms to 23,200 req/s and breaks away by 27,800.

## Reproduce

On an x86_64 Linux host with systemd, Docker and `build-essential libssl-dev`:

```bash
git clone https://github.com/ethlo/r7 && cd r7
sudo benchmark/bench.sh
```

The report and a tarball of everything it measured, including `host.txt` with the image
digest, CPU, kernel and tuning, land in `benchmark/results/`. The tuning is restored when the
script exits.
