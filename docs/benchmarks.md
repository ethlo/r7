# Benchmarks

Every figure here is a delta against a baseline in which the load generator talks straight to
the backend. An absolute "r7 does N req/s" says more about the hardware, the backend and the
load generator than about the gateway, so this page reports what putting r7 in the request
path costs, with throughput and tail latency measured together.

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
  (header filters) and `journal` at `METADATA`, `HEADERS` and `FULL`, over a browser-shaped GET,
  a 36-header GET and a 1 KB JSON POST.
- **Repeats.** Three runs per configuration, each with a fresh JVM and a discarded warmup.
- **Tools.** wrk finds saturation throughput. wrk2 holds a fixed rate and records latency in an
  HdrHistogram, so its p99 and p99.9 are free of coordinated omission.
- **Verdict.** A run is published only when its report says PUBLISHABLE: pulled image, clean
  tree, every tuning step applied, every run valid, repeats within 5%.

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
