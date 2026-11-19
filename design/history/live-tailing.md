# Live tailing: microseconds between write and read

> **History, not maintained.** Steps 1 to 3 shipped and describe how tailers wait today (`CommitSignal`, `R7Tailer.awaitNewData`). Step 4, the doorbell, is parked and not planned: see [`../plans/README.md`](../plans/README.md).

Status: steps 1–3 done; step 4 proposed.

## The question

Today a tailer sees an exchange up to one `poll_interval` (default 1 s) after the gateway
journaled it: each tick lists the journal directory, maps each segment again and decodes from
its checkpoint. Could gateway, tailers and reaper share an in-memory feed instead, for
microsecond latency, and keep the files as the durable, offline-capable layer underneath?

## The files already are shared memory

A segment is a `MAP_SHARED` mapping of a file in the page cache. The tailer's mapping of the
same file is the same physical pages. An entry is visible to a reader the instant the writer
stores its magic behind the release fence (`FORMAT.md`: the magic is the commit), with no
`write(2)`, no copy and no fsync in between. The latency is all in the reader:

| Cost today | Where |
| --- | --- |
| up to `poll_interval` waiting | `Thread.sleep` in the tailer loop |
| `Files.list` of the directory | every tick |
| a fresh `map` of each segment it reads | every tick |

So the data path does not need replacing. What is missing is a cheap way for the reader to
learn that there is something new, and a reader that stays mapped instead of starting over
every tick.

## Why not a socket feed of the data

A second data path (the gateway pushing encoded events over a Unix socket or TCP to
subscribers) would duplicate what the mapping already gives for free, and costs the request
path in exactly the places it is kept lean:

- every event serialised and written twice, once to the segment and once to each subscriber;
- a subscriber that falls behind needs either buffering in the gateway (memory and
  allocation on the hot path) or dropping, after which it must fall back to the files anyway;
- the at-least-once machinery (checkpoints, `OpenExchanges`, the stall-and-retry contract) is
  built on positions in segments; a stream has none of those and would need its own.

It only wins across hosts, where there is no shared page cache. That is a different feature
(shipping), and a tailer next to the journal that forwards over the network does it without
touching the gateway.

## Proposal

1. **Keep the active segment mapped.** A tailer holds the mapping of each shard's active
   segment across ticks and resumes decoding at its in-memory position; it lists the
   directory only to find the next segment, when the current one is sealed. This removes the
   per-tick `list` and `map`, and is worth doing on its own.

2. **A committed-offset word per shard, in a control file.** The writer keeps a small mapped
   `shard-<id>.ctl` beside the segments and, after each commit, release-stores the segment
   sequence and end offset there. A reader checks it with one acquire load to know whether to
   decode at all. Recovery ignores and rewrites the file; it is a hint, never a source of
   truth. The segment, its magics and its sequence numbers stay the only authority, and the
   reader still validates everything it reads exactly as now.

3. **Wait without a syscall per entry.** The reader spins briefly (`Thread.onSpinWait`), then
   parks with growing sleeps up to `poll_interval`, re-checking the offset word. Under load it
   never sleeps and latency is the time to decode; when idle it costs nothing measurable.

4. **Optional doorbell for idle wake-up.** If parking latency matters when traffic is sparse,
   the control file gets a `waiting` flag that a reader sets before it parks, the same pattern
   as io_uring's `NEED_WAKEUP`. After a commit the writer does one plain load of the flag and
   only when it is set sends one byte on a Unix domain socket (`SocketChannel` with
   `UnixDomainSocketAddress`). Under load the flag is never set and the gateway makes no
   syscall at all.

Expected latency: about decode time under load (single-digit microseconds), and the parked
interval or one doorbell round trip (tens of microseconds) when idle. Durability, checkpoints,
at-least-once delivery are untouched. The reaper does not need to be live, but it does gain
from the tailers saying how far they have got: it can delete a segment as soon as every tailer
is done with it, instead of after a TTL sized for the slowest one. The tailers' checkpoint
files already say exactly that, and are written at once when a segment is finished, so the
reaper reads them (`tailers` and `min_age` in `reaper.yaml`, `TailerProgress`) rather than
anything new being added to the gateway.

## Constraints

- Gateway and tailer must share a page cache: the same host and the same underlying file (a
  bind mount or `hostPath`, not a copy). Across hosts this degrades to today's polling, which
  is the offline mode the files already provide.
- The control file is written by the gateway into the journal directory, which tailers mount
  read-only. They only read it; the doorbell socket needs a directory both can write, or the
  reader creates it in its own and the gateway connects to it.
- The hot-path addition is one release store per commit (and, with the doorbell, one load).
  That has to show up as nothing in `RequestPathCostTest` and in `perf stat` instructions per
  request before it ships.

## Step 1: measured

`TailerTickBenchmarkTest` (opt-in, `-Dr7.bench=true`): two shards, 200+ retained sealed
segments, one tailer. Before and after:

| | idle tick | tick with one new exchange per shard |
| --- | --- | --- |
| before | ~300 µs | ~500 µs |
| after | ~13 µs | ~60 µs |

Where the idle tick went: listing and resolving the directory (~150 µs, growing with retained
segments), walking every segment and mapping the active ones again (~60 µs), and rewriting an unchanged checkpoint
file (~90 µs, and ~200 µs when it had changed). So step 1 became three changes: active
segments stay mapped; while every shard is reading its active segment the listing is skipped
until a seal magic shows up in one of those mappings (or 5 s pass); and the checkpoint file is
written only when it changed, at once for a change in segment state and at most once a second
while only read positions move.

It also found a delay at every rotation. The writer stamps the seal record at once but fsyncs
and renames the segment on another thread; until the rename landed the tailer read the
still-active-named segment as active, called it unfinished, and blocked its shard, so the new
segment went unread for as long as the fsync took. A segment carrying the seal magic is now
read as sealed whatever its name.

## Steps 2 and 3: measured

`CommitSignal` is the control file (`shard-<id>.ctl`, one cache line holding a counter the
writer release-stores after every commit and rotation) and `R7Tailer.awaitNewData` the wait:
it spins 20 µs, then parks from 10 µs doubling to 1 ms, comparing the counters with their
values at the start of the tick. The tailer apps call it instead of sleeping.

`TailerLatencyBenchmarkTest` (opt-in), commit of the end event to delivery, one writer:

| gap between exchanges | p50 | p99 |
| --- | --- | --- |
| 50 µs | 46 µs | 0.9 ms |
| 1 ms (tailer parked between them) | 158 µs | 0.8 ms |

Before, with the default 1 s poll, the median was half a second. When the tailer is idle the
latency is set by the park, which is capped at 1 ms to keep an idle tailer at around a
thousand cheap wake-ups a second; step 4 would remove it.

Gateway cost, `perf stat` instructions per request (`compare.sh`, two JVMs each): HEADERS
journaling 398k and 382k before, 392k and 388k after; passthrough and filtered routes moved
by the same amount either way. The one store per entry is inside the noise.

## Order

Step 1 first, measured with the existing tailer benchmarks; steps 2 and 3 together, since the
offset word without a waiting reader buys little; step 4 only if idle latency turns out to
matter for a real consumer.
