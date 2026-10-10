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

Results to be added.

## Results

Results to be added.

## Reproduce

On an x86_64 Linux host with systemd, Docker and `build-essential libssl-dev`:

```bash
git clone https://github.com/ethlo/r7 && cd r7
sudo benchmark/bench.sh
```

The report and a tarball of everything it measured, including `host.txt` with the image
digest, CPU, kernel and tuning, land in `benchmark/results/`. The tuning is restored when the
script exits.
