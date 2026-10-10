# Journal compression per batch, off the request thread

> **Plan, not built.** Proposed and agreed 2026-10-10. Nothing here describes how r7 behaves today.

## The problem

With zstd on (the default), the journal is the largest cost on the request path. On a 12-CPU
host with `METADATA` journaling and the browser workload, single unpinned runs gave:

| Journal | req/s | vs passthrough |
|---|---|---|
| zstd, 2 shards (today's default) | 79.6k | −38.6% |
| zstd, 8 shards | 95.1k | −24% |
| `compression: none` | 111.8k | −13% |

These are single unpinned runs: the motivation, not a measurement. A `bench.sh --repeat 3` run
has to confirm the ranking before this plan is built.

Compression costs this much because of where it runs, not because of the level. FORMAT.md §4.4
keeps one zstd stream per 32 KB block and flushes it after every entry, so that every entry is
committed by its own magic. Two consequences follow:

- **The cost is per flush, not per byte.** A `METADATA` exchange is five entries of about 160
  bytes each. In a single-shard microbenchmark, zstd added about 4.2 µs per exchange to the
  5.0 µs of an uncompressed one. Fast levels (−1, −5) changed nothing, and compressing each entry
  on its own was slower still and barely shrank anything (693 bytes per exchange against 796
  plain).
- **It runs under the shard's monitor.** The stream is shared, so compression cannot leave the
  monitor, and every request thread on the shard queues behind it. With four threads on one
  shard, the same microbenchmark dropped from 109k exchanges/s to 55k. Uncompressed, it held 201k.

A larger default `shard_count` (#203) shortens the queue. It does not remove the per-flush cost
or the monitor.

`compression: none` is not the answer either: it writes about 3.8 times the bytes (836 against
222 per exchange). On cloud instances, I/O is billed and capped.

## The proposal

Request threads stop compressing. They append each entry's plain §4.2 content to a staging
buffer for their shard, holding the lock only for that copy. One writer thread per shard
compresses a full staging buffer as a single zstd frame and places it in the segment as one
**batch**.

```
request thread                     shard writer thread
--------------                     -------------------
encode FlatBuffer (no lock)
lock; copy content into stage A   swap stage A for B when A is full
(Sequence left 0); unlock          or when the flush interval passes;
                                   rotate first if A's worst case
                                   does not fit the segment;
                                   stamp A's Sequences;
                                   compress A as one frame;
                                   place it; write its magic last
```

- **Format.** Batches get a new codec value, 2, and codec 1 is removed outright: no
  compatibility before 1.0 (decided 2026-10-10). Segments left over from an older gateway carry
  codec 1, which a new reader sets aside under the existing unknown-codec rule
  (`anUnknownCodecIsSetAside`); it never deletes or misreads them. An old reader sets aside codec
  2 the same way, so the gateway, tailer and reaper upgrade together, after the tailer has caught
  up: a codec-1 segment still waiting for it when it upgrades is set aside, not delivered. A batch
  is one record, framed, split and checked exactly as §4.3 frames an entry today. Its content is `PlainLength (4)`, `EntryCount (4)` and one zstd frame. Decompressed, the
  frame is the batch's §4.2 entries back to back, each with its own Sequence, so §4.2 is
  unchanged inside a batch. The stream flags, PAD fragments and per-entry flushes go away.
- **Commit.** The magic is still the commit, per batch instead of per entry: the writer thread
  writes a batch's magic last, behind the same release fence. A reader still never scans for a
  magic, and still resumes after damage only at a block boundary.
- **Staging.** Two buffers per shard, 32 KB of plain content each. A request thread that finds
  both full waits, so backpressure behaves as it does today, only less often. Entries are never
  dropped. An entry too large for a stage (a body chunk at `FULL`) makes the writer thread flush
  the stage first and then write that entry as a batch of its own, so the order stays the
  Sequence order.
- **Flush interval.** At low traffic a stage fills slowly, so the writer thread also flushes a
  stage that is not full once it has waited a fixed interval. 10 ms is proposed, not
  configurable at first.
- **Sequence.** Only the writer thread assigns Sequences, after it knows where the batch goes.
  Rotation restarts Sequence at 1, so a Sequence stamped before placement could open a new
  segment above 1. The writer reserves the batch's worst case (zstd's bound for the stage) in the
  current segment, rotates first if that does not fit, then stamps the stage's entries with
  contiguous Sequences at the offsets it recorded while staging, and only then compresses.
  Request threads copy content with Sequence 0, as `fillPlain` already does today.
- **Rotation and close.** Only the writer thread touches the segment, so rotation and sealing
  move to it. `close()` flushes both stages before sealing.
- **Oversized entries.** A request thread with an entry larger than a stage does not copy it.
  It queues the entry on the stage as a reference and waits, so the writer thread flushes what is
  staged, writes the entry as a one-entry batch, and only then releases the thread and its
  buffer. A one-entry batch has the same layout as any other, so codec 2 needs no flags.
- **Failures (decided 2026-10-10).** Writes become asynchronous, and that changes the failure
  contract. Today `writeEntry` has published the magic before it returns, so a disk-full or
  mapping failure fails the request whose entry it was. With staging, the requests whose entries
  are staged have already returned when the writer thread fails. A full disk is a system error,
  not an r7 error, and the in-flight entries are not r7's to save. So the writer thread records
  the failure, the shard refuses every later append (those requests fail closed, as today),
  gateway health goes to ERROR so `/ready` returns 503, and the staged entries it could not place
  are reported as lost by count. There is no complete Sequence range to report: the batch the
  writer was placing may already be stamped, but the filling stage never is. Making each
  request wait for its batch's commit was rejected: it keeps today's contract but gives back the
  latency this plan exists to remove. `r7-journal-mmap/README.md` §5 changes to say so.
- **Low disk (decided 2026-10-10).** So that a full disk is seen coming, the journal reports a
  health component: WARN when free space on `work_dir` is below what two more segments per shard
  need ($2 \times$ `shard_count` $\times$ `shard_size`). It shows in the dashboard header and
  the `/health` and `/ready` bodies, and rolls up into `r7_gateway_health`, as a rejected
  routes.yaml does. It does not depend on batching and ships first (#208).
- **Byte counts (decided 2026-10-10).** `r7_route_journal_bytes_total` counts bytes written to
  the journal, per route, from what the write calls return. A batch mixes routes, so its
  compressed size cannot be attributed to them exactly. The per-route counter therefore counts
  plain (uncompressed) bytes and is renamed to say so, and a new per-shard counter counts the
  compressed bytes placed. Both are breaking metric changes, made before 1.0.

## What it buys

Measured in the sandbox on 50,000 `METADATA` exchanges written uncompressed, then compressed in
chunks the way a batch would be (zstd-jni 1.5.7, level 1):

| Unit | Bytes per exchange | zstd CPU per exchange |
|---|---|---|
| Per-entry flush (today) | 219 | about 4.2 µs |
| 16 KB batch | 94 | 1.6 µs |
| 32 KB batch | 82 | 1.1 µs |
| 64 KB batch | 76 | 1.0 µs |

The synthetic exchanges repeat more than real traffic does, so real ratios will be lower for
both. Today's per-entry figure from the microbenchmark (219 bytes) matches what the host wrote
(222), so the comparison between the two rows is fair.

- **The request path costs about what `none` costs today.** A request holds the lock for a copy
  of about 160 bytes. It never compresses, never touches the mapping, and so never takes a page
  fault or a writeback stall while holding the lock. That last stall was the main finding of
  [`../history/journal-write-contention.md`](../history/journal-write-contention.md), and this
  moves it off the request thread entirely.
- **Compression CPU drops by about three quarters**, and it moves to threads that a saturated
  host still has to schedule. At saturation, throughput should land between today's zstd and
  `none`, close to `none`. That needs a `bench.sh --repeat 3` run to state.
- **The journal shrinks by about a further 2.7 times.** Whole-batch frames compress better than
  a stream flushed every 160 bytes.

The earlier objection to a writer thread per shard was that one writer was about 35% busy at
20k req/s, so two shards would have capped near 55 to 60k req/s. That figure was with per-entry
flushes at `HEADERS`. At a quarter of the CPU per byte, and with the shard count following the
CPUs (#203), one writer per shard has headroom.

## What it costs

**Tailer latency.** Today an entry is visible to a tailer the moment its magic is written. In
the sandbox, from an exchange's end entry to the tailer's consumer, one run of
`TailerLatencyBenchmarkTest` gave a p50 of about 0.06 to 0.2 ms and a p99 of about 0.6 to 3 ms. With batches, an entry is visible when
its batch is placed. Nominally the delay is the time to fill a stage, capped by the flush
interval, plus the writer thread's time to compress and place the batch ($t_{\text{write}}$,
under 0.1 ms for 32 KB in the sandbox measurement):

$$
t_{\text{visible}} \approx \min\left(\frac{S}{r \cdot b},\; T_{\text{flush}}\right) + t_{\text{write}}
$$

$S$ is the stage size (32 KB), $r$ the request rate on the shard, $b$ the plain bytes per
request, and $T_{\text{flush}}$ the flush interval (10 ms).

Nominal fill delay, without $t_{\text{write}}$:

| Load per shard | `METADATA` ($b \approx 800$ B) | `FULL`, one 4 KB body ($b \approx 4.8$ KB) |
|---|---|---|
| 10k req/s | 4 ms | under 1 ms |
| 1k req/s | 10 ms (flush) | 7 ms |
| idle, one request | 10 ms (flush) | 10 ms (flush) |

This is not a bound. When the writer thread falls behind (a disk stall, a rotation, a host
short of CPU), both stages fill, the delay grows by the writer's backlog, and request threads
wait for a free stage. That is the same backpressure as today's monitor, moved: the client waits
only then, as it does today when the monitor is contended. In steady state, tailers (WARC files,
JSON lines and anything downstream) get each exchange about 10 ms later than today at worst.

**Process crash.** Today a committed entry is in the page cache, so it survives the gateway
process being killed. With staging, entries not yet placed are in the process's memory, and a
`kill -9` or an OOM kill loses them: at most both stages, 64 KB of plain entries per shard (one stage being compressed,
one filling). At low traffic that is the last flush interval's entries. At saturation, with the
writer thread behind, it is the full 64 KB, about 80 `METADATA` exchanges. A graceful shutdown flushes
first. Power loss is unchanged, since neither design calls fsync. This contradicts
`r7-journal-mmap/README.md` §2.2 ("MUST NOT maintain user-space write buffers for durability"),
which would have to change, and the loss has to be stated where durability is documented.

**Damage.** A damaged batch costs all of its entries: about 40 `METADATA` exchanges for a 32 KB
stage. Today damage already costs up to a block (32 KB), so the bound is about the same.

**Threads.** One more platform thread per shard, beside the fault-ahead thread. With the shard
count following the CPUs and capped at 16, that is at most 16.

## Complexity

This changes the most intricate part of r7, so it is a format change done with the same care as
format version 2.

- **Writer (`R7fJournal`).** The compression path is replaced by staging and a writer thread.
  The monitor shrinks to the staging copy, and segment state becomes single-threaded, which is
  simpler than today's. Rotation, close and the commit signal move to the writer thread.
- **Format (`FORMAT.md` §4.4).** It is rewritten for batches. Codec 1, its stream flags and its
  PAD fragments are removed, with their decoder.
- **Reader, recovery and reassembler.** A batch yields many entries. The decoder iterates the
  §4.2 entries inside a batch and checks their Sequences exactly as it does across entries
  today. Recovery seals before an uncommitted batch, as it does before an uncommitted entry.
- **Checkpoints (invariant 2).** A checkpoint has to be able to sit inside a batch: either a
  batch offset plus the Sequence delivered up to, or the tailer re-decodes the batch and skips
  entries up to its last delivered Sequence. The second needs no checkpoint format change.
- **Tests.** Every invariant in [`../journal-invariants.md`](../journal-invariants.md) gets
  batch cases: the commit (an unplaced batch is invisible), the checkpoint inside a batch,
  Sequence gaps inside a batch, damage costing a batch. `JournalCompressionTest` is rewritten,
  `R7fTestFraming` learns the new codec, and the fuzz targets cover batch content.

A rough size: the writer change is several hundred lines, the reader and recovery changes are
smaller, and the tests are the largest part.

## Rejected, measured

- **zstd fast levels.** No CPU gain, because the cost is per flush.
- **Each entry compressed on its own, outside the monitor.** Small entries barely compress
  alone, and each new frame is expensive.
- **`compression: none` as the default.** It writes 3.8 times the bytes, and I/O is billed and
  capped on cloud instances.

## Settled defaults

- **Flush interval: 10 ms, fixed.** A setting is one more thing to explain. A fixed value is
  enough until a deployment needs otherwise.
- **Stage size: 32 KB.** 64 KB compresses 7% better, but doubles the fill time and the entries
  lost to damage. The `bench.sh` run that confirms the plan can try both.
- **No batching with `compression: none`.** Without compression there is nothing to batch for,
  so `none` keeps today's direct, per-entry write path.
