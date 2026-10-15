# Profiling the non-journalled GET

> **History.** Profiled 2026-10-15; the changes below shipped in one PR. Read this for why the
> upstream read timeout is enforced by the deadline sweeper, why the poller mode is still 1, and
> where the remaining cost is. `benchmark/profile.sh` repeats every measurement here.

## Setup

A 4-vCPU cloud VM (Xeon @ 2.1 GHz, no SMT, KVM): nginx on CPU 0, the gateway on CPUs 1-2, wrk on
CPU 3, the `api-get` workload (10 headers, 2 KB JSON), 200 connections, journal `NONE`, JDK 27
and G1 (what the JVM picks on two CPUs), the image's JVM flags without the AOT cache. A fresh
JVM per variant, 45 s warmup, 3 × 15 s, variants interleaved.

The metric is CPU per request: the busy time of the gateway's two CPUs (user, system, irq and
softirq) divided by the requests served. Throughput stops being informative here once the
gateway gets cheaper, because the single-core backend and wrk saturate too; CPU per request
keeps showing the difference and varies by about ±3% between runs, against ±5-10% for req/s.

## Where the time went

| | CPU µs per request (user / system / softirq) | req/s |
|---|---|---|
| nginx as a proxy, same CPUs | 38 (14 / 12 / 11) | 34.0k |
| r7 before | 97 (40 / 37 / 19) | 19.6k |
| r7 after | 68.5 (31 / 22 / 14) | 27.7k |

Medians of six runs each (two JVMs × three). p99 went from 17-34 ms to 14 ms.

### Timers for every upstream read

The upstream socket had `SO_TIMEOUT` set to `read_timeout`. On a virtual thread a read that has
to wait parks with a timeout: the JDK arms a task in the ForkJoinPool's `DelayScheduler` when the
thread parks (`VirtualThread.afterYield → ForkJoinPool.schedule → DelayScheduler.pend`) and
cancels it when the data arrives (`cancelTimeoutTask → DelayScheduler.pend`), and both wake the
scheduler thread with a futex. That was 8% of the CPU samples on the request threads plus 7% in
the `DelayScheduler` thread, and most of the kernel's futex time: about a quarter of the cost of
a request.

`max_request_time` already had a cheaper mechanism: one sweeper thread for every upstream,
walking the exchanges in flight every 250 ms. The read timeout now uses it too. `LineReader`
notes when its socket read started (a `volatile long`, set and cleared around the read; the
upstream is only ever read through it, the tunnel excepted, which has no read timeout), and the
sweeper closes a connection whose read has waited past `read_timeout`, after marking it so the
read reports a `SocketTimeoutException("Read timed out")`. Everything downstream of that
exception, retry on a reused connection and the 504 included, is unchanged. The price is
precision: a read now ends up to 250 ms past its timeout, the same as `max_request_time`.

### The poller handoff

With platform-thread pollers (`jdk.pollerMode=1`, r7's default since the stalls described in
`upstream-client.md`), every read that waits goes through a poller thread's `epoll_wait`, an
unpark into the FJP's submission queue and a futex wake of a carrier. Per-carrier pollers save
7-8 µs of system time per request: with the timer gone, mode 2 measured 61 and mode 3 58 µs
against mode 1's 70. Both still stalled new connections at saturation (`wrk -c1000`, a fresh JVM
per run: mode 2 in 4 runs of 8, mode 3 in 3 of 8, mode 1 in none). Helidon accepts on a platform
thread, so a new connection's virtual thread waits in the external queue, which a busy carrier
reads only when its local queue is empty. Mode 1 stays until Helidon or the JDK changes that.

### Smaller costs

- **`Vary: Accept-Encoding` on every response.** Helidon's automatic content encoding parsed
  `Accept-Encoding` for every response, to pick the identity encoder, and added the `Vary`
  header whether or not the upstream sent one. A proxy relays the upstream's encoding, so r7
  turns it off per response.
- **The idle pool's size.** `Target.release` called `ConcurrentLinkedDeque.size()`, which walks
  the deque, on every request (about 1% of samples); the pool now counts.
- **A copy buffer per response.** The body was copied from `LineReader`'s buffer into a new 8 KB
  array before being written; it is now written from the reader's buffer.
- **Request headers.** Copying Helidon's headers into r7's built a list per header through
  `allValues()`; single-valued headers, nearly all of them, now go through `get()`.

## What is left

After the change, on the same host:

| Share of the gateway's CPU | |
|---|---|
| Kernel: socket writes, including loopback delivery and waking the reader | 34% |
| Kernel: socket reads, epoll and scheduling | 18% |
| Helidon: prologue and header parsing and validation, response headers and writing | 17% |
| r7: request head, upstream response, header copies, relay | 15% |
| Virtual-thread scheduler and poller | 7% |

`strace -c` counts two writes and about 2.2 reads per request, which is the minimum for a proxy:
the kernel share is loopback TCP and cross-CPU wakeups that nginx pays too.

The Java side is a flat tail. The structural part is that headers pass through two models:
Helidon parses and validates the request's into its own map and r7 copies them into its own;
r7 parses the upstream's response head and rebuilds a Helidon map, which Helidon validates again
before writing. Helidon's API has no way to write a response head it did not build, so removing
that round trip means owning the HTTP/1.1 server side, which is not worth it for what is left.

Allocation was about 36 KB per request before the copy buffer went, most of the rest Helidon's per-response buffers
(`ClosingBufferedOutputStream`, `GrowingBufferData`). G1's young collections cost about 0.5% of
CPU.

The JIT is not a factor. `-XX:+PrintInlining` shows no hot r7 call site failing to inline that
matters; the "hot method too big" entries are methods entered once per request
(`Http1.requestHead`, `GatewayPipeline.continueUpstream`, Helidon's `Http1Connection.route`). The
largest C2 methods are `UpstreamRelay.relayResponse` (39 KB of machine code with its inlined
callees), `BlockingGateway.handle` (26 KB) and `Http1.requestHead` (22 KB). Deoptimizations come
in one burst when clients close their connections, not in steady state.
