# Live tailing: microseconds between write and read

Status: proposal, not started.

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
at-least-once delivery and the reaper are untouched; the reaper is age-based and gains nothing
from being live.

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

## Order

Step 1 first, measured with the existing tailer benchmarks; steps 2 and 3 together, since the
offset word without a waiting reader buys little; step 4 only if idle latency turns out to
matter for a real consumer.
