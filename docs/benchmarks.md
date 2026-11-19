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
and wrk2 `44a94c1`. The run's [report](assets/benchmarks/20261011-093954/REPORT.txt),
[`environment.txt`](assets/benchmarks/20261011-093954/environment.txt) (with the collector each JVM
ran, `gc_gateway` and `gc_peer_cloud-gateway`) and
[`host.txt`](assets/benchmarks/20261011-093954/host.txt) are published as they came out of it.
The report was written by the verdict before [#221](https://github.com/ethlo/r7/pull/221) and says NOT PUBLISHABLE. Every reason
it lists is a row the current verdict marks instead of failing: the nginx peer at the backend's
limit, and the wrk2 runs of cloud-gateway and of the browser page with `FULL`, whose
configurations saturate below 20,000 req/s. The
[current verdict over the same result files](assets/benchmarks/20261011-093954/verdict-current.txt)
finds no problem.

The six cores are split four ways: nginx on one, **the gateway on two**, the load generator on two,
the OS on one. Everything the gateway does, including the journal, shares those two cores. The
absolute throughput below reflects that allocation: read it per core, and compare scenarios with
each other.

## Results

These figures give an indication of the cost, not a measurement to the decimal. They come from one
run, and every gateway throughput figure carries the spread of its three repeats. Those repeats run against
one gateway process, one after another, so the spread is the noise within a run, not between runs. A difference
smaller than that spread is not a finding.

### Latency at a fixed 20,000 req/s

wrk2 holds the rate and corrects for coordinated omission, so these are the figures to read for
latency. All runs use 200 connections. 20,000 req/s is 46% of what the passthrough gateway
saturates at on two cores for the API requests and 62% for the browser page. With the `HEADERS`
journal the browser page saturates at 24,000 req/s, so that rate is 83% of capacity; with `FULL` it
saturates at 14,900 req/s, below the rate, and that cell is left out. Milliseconds: p50 / p99 /
p99.9.

<svg class="r7-chart" viewBox="0 0 560 612" role="img" aria-labelledby="r7-lat-title" xmlns="http://www.w3.org/2000/svg"><title id="r7-lat-title">Latency at a fixed 20,000 req/s: p50 to p99.9 per scenario, p99 marked, for each request type</title><line class="range" x1="190" x2="250" y1="14" y2="14"/><circle class="dot off" cx="228" cy="14" r="4"/><text x="260" y="18">p50 to p99.9, dot at p99 (ms)</text><text class="direct" x="0" y="48">Browser page GET</text><line class="grid" x1="190.0" x2="190.0" y1="56" y2="204"/><line class="grid" x1="277.5" x2="277.5" y1="56" y2="204"/><line class="grid" x1="365.0" x2="365.0" y1="56" y2="204"/><line class="grid" x1="452.5" x2="452.5" y1="56" y2="204"/><line class="grid" x1="540.0" x2="540.0" y1="56" y2="204"/><text x="0" y="76.0">No gateway (nginx direct)</text><g><title>No gateway (nginx direct), Browser page GET: p50 1.0 / p99 2.2 / p99.9 2.6 ms</title><line class="range" x1="207.5" x2="235.5" y1="72.0" y2="72.0"/><line class="range" x1="207.5" x2="207.5" y1="67.0" y2="77.0"/><line class="range" x1="235.5" x2="235.5" y1="67.0" y2="77.0"/><circle class="dot base" cx="228.5" cy="72.0" r="4.5"/><rect x="190" y="60.0" width="350" height="24" fill="transparent"/></g><text x="0" y="100.0">r7 passthrough</text><g><title>r7 passthrough, Browser page GET: p50 3.1 / p99 7.8 / p99.9 17.5 ms</title><line class="range" x1="244.2" x2="496.2" y1="96.0" y2="96.0"/><line class="range" x1="244.2" x2="244.2" y1="91.0" y2="101.0"/><line class="range" x1="496.2" x2="496.2" y1="91.0" y2="101.0"/><circle class="dot off" cx="326.5" cy="96.0" r="4.5"/><rect x="190" y="84.0" width="350" height="24" fill="transparent"/></g><text x="0" y="124.0">r7 with three header filters</text><g><title>r7 with three header filters, Browser page GET: p50 2.6 / p99 6.6 / p99.9 8.7 ms</title><line class="range" x1="235.5" x2="342.2" y1="120.0" y2="120.0"/><line class="range" x1="235.5" x2="235.5" y1="115.0" y2="125.0"/><line class="range" x1="342.2" x2="342.2" y1="115.0" y2="125.0"/><circle class="dot off" cx="305.5" cy="120.0" r="4.5"/><rect x="190" y="108.0" width="350" height="24" fill="transparent"/></g><text x="0" y="148.0">Journal METADATA</text><g><title>Journal METADATA, Browser page GET: p50 2.6 / p99 5.5 / p99.9 10.3 ms</title><line class="range" x1="235.5" x2="370.2" y1="144.0" y2="144.0"/><line class="range" x1="235.5" x2="235.5" y1="139.0" y2="149.0"/><line class="range" x1="370.2" x2="370.2" y1="139.0" y2="149.0"/><circle class="dot off" cx="286.2" cy="144.0" r="4.5"/><rect x="190" y="132.0" width="350" height="24" fill="transparent"/></g><text x="0" y="172.0">Journal HEADERS</text><g><title>Journal HEADERS, Browser page GET: p50 3.4 / p99 8.2 / p99.9 17.0 ms</title><line class="range" x1="249.5" x2="487.5" y1="168.0" y2="168.0"/><line class="range" x1="249.5" x2="249.5" y1="163.0" y2="173.0"/><line class="range" x1="487.5" x2="487.5" y1="163.0" y2="173.0"/><circle class="dot off" cx="333.5" cy="168.0" r="4.5"/><rect x="190" y="156.0" width="350" height="24" fill="transparent"/></g><text x="0" y="196.0">Journal FULL</text><text x="196.0" y="196.0">above capacity</text><text class="direct" x="0" y="226">API GET</text><line class="grid" x1="190.0" x2="190.0" y1="234" y2="382"/><line class="grid" x1="277.5" x2="277.5" y1="234" y2="382"/><line class="grid" x1="365.0" x2="365.0" y1="234" y2="382"/><line class="grid" x1="452.5" x2="452.5" y1="234" y2="382"/><line class="grid" x1="540.0" x2="540.0" y1="234" y2="382"/><text x="0" y="254.0">No gateway (nginx direct)</text><g><title>No gateway (nginx direct), API GET: p50 1.0 / p99 2.1 / p99.9 2.4 ms</title><line class="range" x1="207.5" x2="232.0" y1="250.0" y2="250.0"/><line class="range" x1="207.5" x2="207.5" y1="245.0" y2="255.0"/><line class="range" x1="232.0" x2="232.0" y1="245.0" y2="255.0"/><circle class="dot base" cx="226.8" cy="250.0" r="4.5"/><rect x="190" y="238.0" width="350" height="24" fill="transparent"/></g><text x="0" y="278.0">r7 passthrough</text><g><title>r7 passthrough, API GET: p50 2.1 / p99 4.4 / p99.9 5.6 ms</title><line class="range" x1="226.8" x2="288.0" y1="274.0" y2="274.0"/><line class="range" x1="226.8" x2="226.8" y1="269.0" y2="279.0"/><line class="range" x1="288.0" x2="288.0" y1="269.0" y2="279.0"/><circle class="dot off" cx="267.0" cy="274.0" r="4.5"/><rect x="190" y="262.0" width="350" height="24" fill="transparent"/></g><text x="0" y="302.0">r7 with three header filters</text><g><title>r7 with three header filters, API GET: p50 2.0 / p99 4.1 / p99.9 5.1 ms</title><line class="range" x1="225.0" x2="279.2" y1="298.0" y2="298.0"/><line class="range" x1="225.0" x2="225.0" y1="293.0" y2="303.0"/><line class="range" x1="279.2" x2="279.2" y1="293.0" y2="303.0"/><circle class="dot off" cx="261.8" cy="298.0" r="4.5"/><rect x="190" y="286.0" width="350" height="24" fill="transparent"/></g><text x="0" y="326.0">Journal METADATA</text><g><title>Journal METADATA, API GET: p50 2.1 / p99 4.3 / p99.9 5.7 ms</title><line class="range" x1="226.8" x2="289.8" y1="322.0" y2="322.0"/><line class="range" x1="226.8" x2="226.8" y1="317.0" y2="327.0"/><line class="range" x1="289.8" x2="289.8" y1="317.0" y2="327.0"/><circle class="dot off" cx="265.2" cy="322.0" r="4.5"/><rect x="190" y="310.0" width="350" height="24" fill="transparent"/></g><text x="0" y="350.0">Journal HEADERS</text><g><title>Journal HEADERS, API GET: p50 2.1 / p99 5.0 / p99.9 13.5 ms</title><line class="range" x1="226.8" x2="426.2" y1="346.0" y2="346.0"/><line class="range" x1="226.8" x2="226.8" y1="341.0" y2="351.0"/><line class="range" x1="426.2" x2="426.2" y1="341.0" y2="351.0"/><circle class="dot off" cx="277.5" cy="346.0" r="4.5"/><rect x="190" y="334.0" width="350" height="24" fill="transparent"/></g><text x="0" y="374.0">Journal FULL</text><g><title>Journal FULL, API GET: p50 2.6 / p99 5.8 / p99.9 9.3 ms</title><line class="range" x1="235.5" x2="352.8" y1="370.0" y2="370.0"/><line class="range" x1="235.5" x2="235.5" y1="365.0" y2="375.0"/><line class="range" x1="352.8" x2="352.8" y1="365.0" y2="375.0"/><circle class="dot off" cx="291.5" cy="370.0" r="4.5"/><rect x="190" y="358.0" width="350" height="24" fill="transparent"/></g><text class="direct" x="0" y="404">API POST</text><line class="grid" x1="190.0" x2="190.0" y1="412" y2="560"/><line class="grid" x1="277.5" x2="277.5" y1="412" y2="560"/><line class="grid" x1="365.0" x2="365.0" y1="412" y2="560"/><line class="grid" x1="452.5" x2="452.5" y1="412" y2="560"/><line class="grid" x1="540.0" x2="540.0" y1="412" y2="560"/><text x="0" y="432.0">No gateway (nginx direct)</text><g><title>No gateway (nginx direct), API POST: p50 1.1 / p99 2.3 / p99.9 2.6 ms</title><line class="range" x1="209.2" x2="235.5" y1="428.0" y2="428.0"/><line class="range" x1="209.2" x2="209.2" y1="423.0" y2="433.0"/><line class="range" x1="235.5" x2="235.5" y1="423.0" y2="433.0"/><circle class="dot base" cx="230.2" cy="428.0" r="4.5"/><rect x="190" y="416.0" width="350" height="24" fill="transparent"/></g><text x="0" y="456.0">r7 passthrough</text><g><title>r7 passthrough, API POST: p50 2.0 / p99 4.3 / p99.9 5.4 ms</title><line class="range" x1="225.0" x2="284.5" y1="452.0" y2="452.0"/><line class="range" x1="225.0" x2="225.0" y1="447.0" y2="457.0"/><line class="range" x1="284.5" x2="284.5" y1="447.0" y2="457.0"/><circle class="dot off" cx="265.2" cy="452.0" r="4.5"/><rect x="190" y="440.0" width="350" height="24" fill="transparent"/></g><text x="0" y="480.0">r7 with three header filters</text><g><title>r7 with three header filters, API POST: p50 2.0 / p99 4.5 / p99.9 5.5 ms</title><line class="range" x1="225.0" x2="286.2" y1="476.0" y2="476.0"/><line class="range" x1="225.0" x2="225.0" y1="471.0" y2="481.0"/><line class="range" x1="286.2" x2="286.2" y1="471.0" y2="481.0"/><circle class="dot off" cx="268.8" cy="476.0" r="4.5"/><rect x="190" y="464.0" width="350" height="24" fill="transparent"/></g><text x="0" y="504.0">Journal METADATA</text><g><title>Journal METADATA, API POST: p50 2.1 / p99 4.4 / p99.9 5.7 ms</title><line class="range" x1="226.8" x2="289.8" y1="500.0" y2="500.0"/><line class="range" x1="226.8" x2="226.8" y1="495.0" y2="505.0"/><line class="range" x1="289.8" x2="289.8" y1="495.0" y2="505.0"/><circle class="dot off" cx="267.0" cy="500.0" r="4.5"/><rect x="190" y="488.0" width="350" height="24" fill="transparent"/></g><text x="0" y="528.0">Journal HEADERS</text><g><title>Journal HEADERS, API POST: p50 2.2 / p99 4.8 / p99.9 7.6 ms</title><line class="range" x1="228.5" x2="323.0" y1="524.0" y2="524.0"/><line class="range" x1="228.5" x2="228.5" y1="519.0" y2="529.0"/><line class="range" x1="323.0" x2="323.0" y1="519.0" y2="529.0"/><circle class="dot off" cx="274.0" cy="524.0" r="4.5"/><rect x="190" y="512.0" width="350" height="24" fill="transparent"/></g><text x="0" y="552.0">Journal FULL</text><g><title>Journal FULL, API POST: p50 2.6 / p99 5.9 / p99.9 17.0 ms</title><line class="range" x1="235.5" x2="487.5" y1="548.0" y2="548.0"/><line class="range" x1="235.5" x2="235.5" y1="543.0" y2="553.0"/><line class="range" x1="487.5" x2="487.5" y1="543.0" y2="553.0"/><circle class="dot off" cx="293.2" cy="548.0" r="4.5"/><rect x="190" y="536.0" width="350" height="24" fill="transparent"/></g><line class="axis" x1="190" x2="540" y1="572" y2="572"/><text class="tick" x="190.0" y="588" text-anchor="middle">0</text><text class="tick" x="277.5" y="588" text-anchor="middle">5</text><text class="tick" x="365.0" y="588" text-anchor="middle">10</text><text class="tick" x="452.5" y="588" text-anchor="middle">15</text><text class="tick" x="540.0" y="588" text-anchor="middle">20</text><text x="365" y="604" text-anchor="middle">milliseconds</text></svg>

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

The filters cost up to 5% on the API requests and 11% on the browser page, whose repeats spread
8.7%. `METADATA` costs 4% and 5% on the GETs and 14% on the POST. `HEADERS` costs 21% to 25%.
`FULL` costs 34% on the API requests and 54% on the browser page, which records 8 KB of HTML with
every exchange.

### Against other gateways

The same run puts two other gateways in r7's place, each as configured in
[`benchmark/peers/`](https://github.com/ethlo/r7/tree/main/benchmark/peers): same port, same two
cores, same 990 MB memory limit, same backend and workloads. One is nginx as a reverse proxy with
upstream keep-alive and no access log. The other is
[cloud-gateway](https://github.com/ethlo/cloud-gateway), Spring Cloud Gateway on WebFlux with one
route and its own capture off, on the same collector as r7. The ratios below are for
these configurations. Saturation in requests per second, each value with the spread of its three repeats:

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
measured, show how p99 grows with offered load. Each configuration is swept up to its own
saturation, so the two lines end at different rates. p99 in milliseconds, on a log scale:

<svg class="r7-chart" viewBox="0 0 640 320" role="img" aria-labelledby="r7-sweep-title" xmlns="http://www.w3.org/2000/svg"><title id="r7-sweep-title">API GET p99 latency against offered load, journal off and FULL</title><line class="grid" x1="56" x2="520" y1="276.0" y2="276.0"/><text class="tick" x="48" y="280.0" text-anchor="end">1</text><line class="grid" x1="56" x2="520" y1="190.7" y2="190.7"/><text class="tick" x="48" y="194.7" text-anchor="end">10</text><line class="grid" x1="56" x2="520" y1="105.3" y2="105.3"/><text class="tick" x="48" y="109.3" text-anchor="end">100</text><line class="grid" x1="56" x2="520" y1="20.0" y2="20.0"/><text class="tick" x="48" y="24.0" text-anchor="end">1,000</text><text class="tick" x="56.0" y="294" text-anchor="middle">10k</text><text class="tick" x="120.4" y="294" text-anchor="middle">15k</text><text class="tick" x="184.9" y="294" text-anchor="middle">20k</text><text class="tick" x="249.3" y="294" text-anchor="middle">25k</text><text class="tick" x="313.8" y="294" text-anchor="middle">30k</text><text class="tick" x="378.2" y="294" text-anchor="middle">35k</text><text class="tick" x="442.7" y="294" text-anchor="middle">40k</text><text class="tick" x="507.1" y="294" text-anchor="middle">45k</text><line class="axis" x1="56" x2="520" y1="276" y2="276"/><text class="label" x="288" y="314" text-anchor="middle">offered load (req/s)</text><text class="label" transform="translate(14,148) rotate(-90)" text-anchor="middle">p99 (ms, log scale)</text><path class="line off" d="M208.1,236.0 L348.6,206.5 L433.6,127.0 L489.1,75.9"/><circle class="dot off" cx="208.1" cy="236.0" r="4"><title>Journal off: 21,800 req/s offered, p99 2.94 ms</title></circle><circle class="dot off" cx="348.6" cy="206.5" r="4"><title>Journal off: 32,700 req/s offered, p99 6.53 ms</title></circle><circle class="dot off" cx="433.6" cy="127.0" r="4"><title>Journal off: 39,300 req/s offered, p99 55.78 ms</title></circle><circle class="dot off" cx="489.1" cy="75.9" r="4"><title>Journal off: 43,600 req/s offered, p99 221.18 ms</title></circle><text class="direct" x="499.1" y="79.9">Journal off</text><path class="line full" d="M111.4,234.9 L204.2,216.9 L259.6,117.3 L297.0,25.4"/><circle class="dot full" cx="111.4" cy="234.9" r="4"><title>Journal FULL: 14,300 req/s offered, p99 3.03 ms</title></circle><circle class="dot full" cx="204.2" cy="216.9" r="4"><title>Journal FULL: 21,500 req/s offered, p99 4.93 ms</title></circle><circle class="dot full" cx="259.6" cy="117.3" r="4"><title>Journal FULL: 25,800 req/s offered, p99 72.38 ms</title></circle><circle class="dot full" cx="297.0" cy="25.4" r="4"><title>Journal FULL: 28,700 req/s offered, p99 863.74 ms</title></circle><text class="direct" x="307.0" y="29.4">Journal FULL</text></svg>


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
