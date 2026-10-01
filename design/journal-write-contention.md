# Journal write contention (in progress)

**Status:** investigated 2026-10-01; findings only, nothing changed yet. Open questions at the
end. The operator-facing guidance is in `docs/performance_tuning.md`. Raw runs are under
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

## Open questions

- **Take the faults out of the monitor without paying for the whole segment up front.** Some
  options:
  - a toucher that keeps a small window ahead of the write position faulted (a few MB per
    shard, not 1.2 GB);
  - `madvise(MADV_POPULATE_WRITE)` over that window, through FFM;
  - claim the slot under the monitor and copy outside it. Faults then happen in parallel, and
    the magic-as-commit rule (`FORMAT.md` §5) still holds, because a reader stops at the first
    zero magic. Rotation and the seal record are where that gets hard; see
    `design/journal-invariants.md`.
- **Default `shard_count`.** 4 removes most of Helidon's monitor contention and its tail, and
  costs no memory without `pre_fault`. It does cost files, and disk while segments are written.
- **Leave `pre_fault` off by default.** It is a win only with memory to spare. The `pre_fault`
  row in `docs/config.md` should say what it costs in a container.
- **Journal page cache fills the container limit within seconds at full load.** Whether that
  matters depends on how fast a tailer consumes and deletes. That is a deployment question for
  `docs/journaling.md`.
