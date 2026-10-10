# Benchmarks

The question is what putting r7 in the request path costs. The suite reports two comparisons.
Against the no-gateway baseline it shows the total cost of the proxy hop, an upper bound while
the load generator shares the host. Against the passthrough run (r7 in the path, no filters, no
journal) it shows what a filter or a journal level adds: same topology, same contention, so the
noise cancels. Filter and journal costs are quoted against passthrough. Throughput and tail
latency are reported together.

## Method

- **One command.** `sudo benchmark/bench.sh` produces the numbers. It measures the published
  image `ghcr.io/ethlo/r7-gateway`, resolved to its digest, with the image's own JVM flags and
  AOT cache.
- **Pinned toolchain.** wrk and wrk2 are built from pinned commits, and the nginx backend image
  is pinned by digest.
- **Tuned host.** SMT and turbo off, the `performance` governor, network sysctls, and every
  other process confined to housekeeping cores through systemd.
- **Separate cores.** nginx, the gateway and the load generator each run on cores of their own.
- **Scenarios.** `baseline` (no gateway), `passthrough` (r7, no filters, no journal), `filtered`
  (header filters), `journal` at `METADATA`, `HEADERS` and `FULL`, and a `sweep` of fixed offered
  rates that shows p99 against load. Each runs over a browser-shaped GET, a 36-header GET and a
  1 KB JSON POST.
- **Repeats.** Three runs per configuration, each with a fresh JVM and a discarded warmup.
- **Tools.** wrk finds saturation throughput. wrk2 holds a fixed rate and records latency in an
  HdrHistogram, so its p99 and p99.9 are free of coordinated omission.
- **Verdict.** A run is published only when its report says PUBLISHABLE: a pulled image (or a `--local`
  build), clean tree, every tuning step applied, every run valid, repeats within 5%.

The full methodology is in [`benchmark/README.md`](https://github.com/ethlo/r7/blob/main/benchmark/README.md).

## Hardware

One run of `sudo benchmark/bench.sh` on a 12th Gen Intel Core i5-12500 (six performance cores,
SMT and turbo off, `performance` governor, 62 GiB RAM, Linux 6.8.0-142). The gateway image was
`ghcr.io/ethlo/r7-gateway@sha256:0b45ac946cecea46f3ed6803674076639878a474e8cdd60c5f539621e8fe6e21`
(Temurin 27+35, the JVM's default collector), wrk `a211dd5` and wrk2 `44a94c1`.

The six cores are split four ways: nginx on one, **the gateway on two**, the load generator on two,
the OS on one. Everything the gateway does, including the journal, shares those two cores. The
absolute throughput below reflects that allocation: read it per core, and compare scenarios with
each other.

## Results

These figures give an indication of the cost, not a measurement to the decimal. They come from one
run. The report's own verdict for that run is not publishable, because some `filtered` runs had
request timeouts and a few configurations varied more than 5% between repeats. This page leaves out
the rows those runs produced and keeps the rest; the throughput `±` column below is the spread the
run measured.

### Latency at a fixed 20,000 req/s

wrk2 holds the rate and corrects for coordinated omission, so these are the figures to read for
latency. 20,000 req/s is about 45% of what the gateway saturates at on two cores. Milliseconds:
p50 / p99 / p99.9.

| Scenario | Browser GET | 36-header GET | 1 KB POST |
| --- | --- | --- | --- |
| No gateway (nginx direct) | 1.0 / 2.0 / 2.2 | 1.0 / 2.3 / 2.6 | 1.0 / 1.9 / 2.2 |
| r7 passthrough | 1.6 / 4.4 / 5.6 | 2.2 / 5.5 / 9.9 | 1.5 / 4.2 / 5.7 |
| r7 with three header filters | 1.8 / 4.7 / 6.5 | 2.1 / 6.8 / 13.0 | 1.5 / 4.2 / 5.7 |
| Journal `METADATA` | 1.9 / 5.3 / 7.5 | 2.3 / 5.5 / 7.5 | 1.7 / 5.4 / 6.6 |
| Journal `HEADERS` | 2.0 / 5.0 / 6.8 | 2.9 / 17.1 / 40.0 | 1.8 / 5.4 / 7.8 |
| Journal `FULL` | 2.3 / 5.7 / 11.9 | 4.0 / 27.1 / 51.2 | 2.2 / 5.3 / 9.1 |

r7 adds about 2 ms at p99 over nginx on this host, and `METADATA` journaling adds under 1 ms
more. At `HEADERS` and `FULL` the 36-header request pays for recording every header: its p99
reaches 17 and 27 ms.

### Saturation throughput

wrk opens every connection and measures the most the gateway sustains. Journaling is compared with
the passthrough run, which has the same topology and the same contention. Its latency figures
understate the tail, so use the table above for latency.

| Scenario | Browser GET | 36-header GET | 1 KB POST |
| --- | --- | --- | --- |
| r7 passthrough (requests per second) | 45,700 | 37,000 | 52,200 |
| Journal `METADATA` | −16% | −14% | −17% |
| Journal `HEADERS` | −29% | −41% | −25% |
| Journal `FULL` | −32% | −45% | −34% |

The header filters cost 4.5% on the POST workload (±5.4%). Their browser and header-heavy rows are
left out because of the timeouts above.

### Where the tail breaks away

Fixed-rate sweeps on the browser workload show how p99 grows with offered load. Milliseconds:

| Offered (req/s) | Journal off: p99 | Offered (req/s) | Journal `FULL`: p99 |
| --- | --- | --- | --- |
| 22,800 | 3.3 | 15,600 | 3.6 |
| 34,200 | 7.9 | 23,300 | 8.1 |
| 41,100 | 8.9 | 28,000 | 50.4 |
| 45,700 | 1,400 | 31,100 | 422 |

With the journal off, the tail stays under 10 ms up to about 41,000 req/s and breaks away near the
45,700 req/s saturation point. With `FULL` it stays under 10 ms to about 23,000 req/s and breaks
away between 28,000 and 31,000 req/s.

## Reproduce

On an x86_64 Linux host with systemd, Docker and `build-essential libssl-dev`:

```bash
git clone https://github.com/ethlo/r7 && cd r7
sudo benchmark/bench.sh
```

The report and a tarball of everything it measured, including `host.txt` with the image
digest, CPU, kernel and tuning, land in `benchmark/results/`. The tuning is restored when the
script exits.
