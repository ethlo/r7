# Journal write contention

> **History, not maintained.** This is the investigation behind fault-ahead, the `shard_count` default of 2 and leaving `pre_fault` off (2026-10-01 to 2026-10-02). The Undertow comparisons date from before Undertow was removed. Operator guidance is in [`docs/performance_tuning.md`](../../docs/performance_tuning.md), and what is still open is in [`../limitations.md`](../limitations.md).

**Status:** investigated 2026-10-01, lock measured 2026-10-02. Page faults in the monitor are
fixed by fault-ahead (`FaultAhead`, see "Fault-ahead" below), and `shard_count` now defaults to 2.
"The lock on Níma" records what the monitor itself costs, now that Níma is the only server; the
remaining levers are in the questions at the end. The operator-facing guidance is in `docs/performance_tuning.md`. Raw runs are under
`benchmark/results/jdeg-*` on the machine that made them (not checked in).

## Symptom

With `HEADERS` journaling on the header-heavy workload at saturation, the Helidon gateway was
well behind Undertow, and both got worse the longer a run went on: in the `journal` scenario
Helidon fell from 70k to 41k req/s over three repeats in one JVM, with p99 rising from 60 ms to
1 s. Undertow fell less, from 82k to 78k req/s, with p99 going from 10 to 150 ms. With
journaling off, Helidon is the faster of the two (146k against 121k req/s).

## Cause

Every journal entry is written inside `R7fJournal.writeEntry`, which is `synchronized` per
shard, and `storage.shard_count` defaults to 1. Two things make that monitor expensive to hold:

1. **Page faults inside the monitor.** `storage.pre_fault` defaults to `false`, so the first
   store to each 4 KB page of a fresh segment faults while the monitor is held. At about
   200 MB/s of journal, that is ~50k faults a second, and every other writer waits behind each
   one. On a real disk, writeback throttling (`balance_dirty_pages`) lands in the same place,
   and it grows as dirty pages pile up. That is the slowdown over time.
2. **How many threads queue.** Undertow writes from its I/O threads, about 20 of them. Helidon
   writes from one virtual thread per connection: 200 in the benchmark, all queued on one
   monitor. Each stall costs more, and the monitor's unfair handoff gives Helidon a long tail
   even once the stalls are gone.

The encoding itself (FlatBuffers, latin-1 copy, redaction) already runs outside the monitor,
and the JFR CPU profiles of the two servers are much alike. Helidon used *less* CPU (31% against
38% JVM user) for less throughput, which pointed at waiting rather than work.

## Measurements

Probe: one gateway, `HEADERS` on both directions, `header-heavy.lua`, wrk `-t10 -c200`, 15 s
warmup, then back-to-back 10 s intervals in the same JVM. JDK 25, ZGC, 20 cores, nginx backend.
Sealed segments were deleted as they appeared so tmpfs would not fill, which also removes most
writeback; so these runs isolate the lock, not the disk.

On tmpfs (`/dev/shm`), steady state:

| storage | Undertow req/s | p99 | Helidon req/s | p99 |
|---|---|---|---|---|
| 1 shard (the defaults) | 64k | 14 ms | 41k | 20 ms |
| 2 shards | 81k | 11 ms | 67k | 19 ms |
| 4 shards | 81k | 12 ms | 81k | 22 ms |
| 8 shards | 88k | 11 ms | 91k | 23 ms |
| 1 shard, `pre_fault` | 89k | 12 ms | 93k | 55 ms |
| 4 shards, `pre_fault` | 88k | 12 ms | 95k | 17 ms |

On the real disk, one shard, with segments deleted as above: `pre_fault` took Undertow from 67k
to 80k req/s and Helidon from 63k to 77k. Neither degraded over six intervals. In the original
runs, which kept the segments, both did, so writeback was the part that changed over time.

A spin-then-yield lock in place of the monitor (CAS, `onSpinWait` for 2048 rounds, then
`Thread.yield()`) was worse on both servers: 45k and 30k req/s on one shard, with p99 of 35 and
250 ms. The monitor is not the problem. Holding it across a fault is.

## Cost in a container

The same images under `docker run -m 8g`. Journals were on a bind-mounted disk directory unless
noted, and **not** deleted, so this is the realistic case. The figures are cgroup v2
`memory.stat`, in MB. ZGC's heap is backed by memfd, so it shows as `shmem`, not `anon`.
`working_set` is `memory.current - inactive_file`, what Kubernetes watches.

| storage | idle `file_mapped` | idle `working_set` | under 30 s load | req/s |
|---|---|---|---|---|
| 1 shard | 193 | 337 | at the 8 GB limit, page cache | 48k |
| 1 shard, `pre_fault` | 1,384 | 1,570 | at the limit | 34k |
| 4 shards | 184 | 336 | at the limit | 48k |
| 4 shards, `pre_fault` | 4,982 | 5,287 | at the limit | 30k |
| 4 shards, `pre_fault`, journals on tmpfs | 4,976 (all `shmem`) | 5,143 | **OOM-killed** | - |

- **`pre_fault` charges the warmed segments at once.** That's one active segment plus
  `WARMED_SEGMENT_DEPTH` (4) queued, plus one being warmed, about 1.2 GB per shard at 200 MB.
  The charge counts as mapped, active page cache. Without `pre_fault` the queued segments are
  sparse and cost nothing until written.
- **Under a memory limit `pre_fault` is slower, not faster.** Journal page cache fills the
  cgroup either way, and reclaim then has to work around the pre-faulted, mapped pages.
  Outside a container, `pre_fault` won 15–25%. Inside one, it lost 30–40%.
- **Extra shards cost nothing in memory without `pre_fault`.** Unwritten segments are sparse.
  Under the container limit, 1 and 4 shards gave the same throughput, because there page-cache
  reclaim, not the monitor, was the bottleneck.
- **Journals on tmpfs** (`emptyDir: {medium: Memory}`, `--tmpfs`) are unreclaimable memory
  charged to the container. r7 has no retention of its own, so without a tailer deleting
  sealed segments, this ends in an OOM kill whatever the settings.

## Fault-ahead

`FaultAhead` keeps the 8 MB past each shard's write position populated, using
`madvise(MADV_POPULATE_WRITE)` through FFM, on one platform thread per shard, in 2 MB chunks.
The writer claims each chunk under the monitor it already holds (a compare, and now and then a
queue offer). The populate call runs outside it.

- **Why `madvise` and not a touching write:** populate prepares a page for writing without
  writing to it. A writer that overtakes the window has already committed entries there, and a
  zeroing touch racing with that would destroy them. Populate leaves them as they are
  (`FaultAheadTest.populatingNeverChangesWhatTheWriterHasWritten`).
- **Arena lifetime:** a shared arena refuses to close while a downcall holds one of its
  segments. The rotation finalizer and `close()` therefore drain the fault-ahead queue before
  closing a segment's arena. Chunks run in order on one thread, so a no-op task submitted after
  them is a fence.
- **Off where it cannot help:** not Linux, a kernel older than 5.14 (probed once, on an
  anonymous page), a failed link, or `pre_fault` on. A failure while running
  costs nothing: the writer faults as before.
- **Native access:** the gateway jars carry `Enable-Native-Access: ALL-UNNAMED` in their
  manifests, as the images' entrypoints do not pass the flag.

Measured, the defaults (1 shard, no `pre_fault`):

| | Undertow before / after | Helidon before / after |
|---|---|---|
| tmpfs | 64k / 94k req/s | 41k / 98k req/s |
| disk | 67k / 82k req/s | 63k / 84k req/s |
| container, `-m 8g` | - | 48k / 54k req/s, idle memory unchanged |

That beats `pre_fault` everywhere, without its memory.

## The lock on Níma

Measured 2026-10-02 with two shards, HEADERS on both directions, 200 connections, the browser
workload, wrk2 at 20k req/s unless noted; one-minute runs on a shared laptop, so directional.

**JFR cannot see this.** On JDK 25, `jdk.JavaMonitorEnter` is not emitted for a virtual thread
blocked on a monitor: 200 virtual threads taking turns on one monitor held 2 ms at a time
(about 8 s of contention) produced no events. The numbers below come from a temporary probe that
timed the wait to enter and the hold around `writeEntry` and `writeCompressed`, with a
log2-bucketed histogram reset after warmup.

| | hold p99 | wait p99 | wait p99.9 | wait max |
|---|---|---|---|---|
| zstd (default) | 33 µs | 4.2 ms | 16.8 ms | 34 ms |
| uncompressed | 8 µs | 16 µs | 1 ms | 67 ms |

- **Holds are short; waits are not.** zstd is about 85% of the hold time, and keeps each shard's
  monitor busy about a third of the time. A virtual thread that blocks unmounts and has to be
  rescheduled onto a busy carrier when the monitor frees, while others barge, so waits run two
  orders of magnitude past holds. Rotation is not a factor: 2–6 per run.
- **The waits are not the request tail.** With a `ReentrantLock` that spins on `tryLock`
  before parking, total wait fell fivefold but request p99.9 barely moved (21 → 18 ms).
  Uncompressed, where waits are negligible, request p99.9 is still about 24 ms against 7 ms
  with the journal off, and identical runs ranged 9–24 ms. Whatever adds the p99.9 is mostly
  outside the monitor. A plain `ReentrantLock` without the spin produced one run at 923 ms
  p99.9, so neither variant was adopted.
- **At saturation the journal is about half the CPU.** Without the journal 157k req/s; HEADERS
  with zstd 77k, uncompressed 88k (p99 1 s, from writeback). Of sampled CPU with zstd: 17.5% in
  zstd under the monitor, 6.7% entering it, 4.6% in `Zstd.compressBound`, 16% encoding outside
  it. `compressBound` is now computed in Java; the difference was below the run-to-run noise
  of instructions per request (about 4%) at 20k req/s, so it is a cleanup, not a measured gain.

## Open questions

- **Compression is the lever left, and both ways out cost something.** A writer thread per
  shard would take every wait off the request threads, but one writer is about 35% busy at 20k
  req/s, so two shards would cap near 55–60k req/s, below today's 77k; it needs four shards to
  come out ahead. Compressing each entry on its own, outside the monitor, is a format change and
  gives up the shared per-block stream's ratio. Neither is worth it unless journaled throughput
  is what limits a deployment. Claiming a slot under the monitor and copying after shortens
  only the uncompressed hold, which is already 8 µs at p99.
- **Default `shard_count`: decided, 2.** With fault-ahead, one shard keeps up on throughput,
  and 4 halved Helidon's tail. 2 was chosen as the balance between the two: half the writers per
  shard for one more open segment. 2 was not measured with fault-ahead on.
- **Leave `pre_fault` off by default.** With fault-ahead it is never a win where fault-ahead
  works. `docs/config.md` and `docs/performance_tuning.md` say so.
- **Journal page cache fills the container limit within seconds at full load.** Whether that
  matters depends on how fast a tailer consumes and deletes. That is a deployment question for
  `docs/journaling.md`.
