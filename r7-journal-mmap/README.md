# r7 Journal MMAP Architecture

## 1. Scope

This document defines the **runtime storage architecture** for r7 journal ingestion using:

* memory-mapped files (mmap)
* append-only writes
* OS page cache as the durability mechanism

It defines:

* write path semantics
* commit semantics
* failure model
* recovery behavior

It does NOT define:

* HTTP semantics
* FlatBuffer schema (see r7fbs)
* routing or execution model

---

# 2. Design Model

## 2.1 Core Principle

The journal is a:

> **single-node, append-only, OS-buffered event log**

All persistence behavior is delegated to the operating system page cache.

The application MUST NOT implement its own persistence layer.

---

## 2.2 Storage Medium

The journal MUST be implemented using:

* memory-mapped file (`mmap`)
* OS page cache writeback
* sequential append writes only

The implementation MUST NOT:

* perform in-place mutation
* perform random writes in hot path
* rely on user-space buffers for durability

A compressed journal stages entries in memory until they are placed as a batch (§4.0). That
staging is for compression, not durability: what is staged is not yet journaled, and a crash
loses it (§8.1).

---

# 3. Write Path Semantics

## 3.1 Append Model

All journal writes MUST be:

* sequential
* contiguous
* append-only

Writes are performed by writing directly into the mapped memory region.

---

## 3.2 OS Ownership of Durability

The system explicitly delegates persistence to the OS:

* Dirty pages are tracked by the kernel
* Writeback scheduling is controlled by OS heuristics
* Persistence timing is NOT deterministic

The application:

* MUST assume data is durable only when observed on disk
* MUST NOT assume durability at write time

---

## 3.3 No Explicit Flush Model

The system MUST NOT rely on:

* `fsync()`
* `fdatasync()`
* `msync(MS_SYNC)`

Optional use of `msync(MS_ASYNC)` MAY be used for:

* memory pressure management
* segment rotation safety checks

But it MUST NOT be part of the durability contract.

---

## 3.4 Page Population Ahead of the Writer

Entries are placed in the segment under the journal's monitor, so a page fault taken there
stalls whoever is placing: every writer to the shard when uncompressed, the shard's writer
thread when compressed. Where the platform supports it (Linux 5.14 and later), a
background thread keeps the pages just ahead of the write position populated with
`madvise(MADV_POPULATE_WRITE)` (`FaultAhead`).

Population:

* MUST NOT write to the segment. A writer may already have committed entries in a range that
  is still to be populated, and population must leave them exactly as they are
* MUST finish with a segment before that segment's arena is closed
* is an optimisation only: if it falls behind, fails, or is unavailable, the writer faults the
  pages itself, as without it

`pre_fault` touches whole segments in the warmer instead, and disables this.

---

# 4. Commit Semantics

## 4.0 Compression

By default (`storage.compression: zstd`) entries are compressed in batches (FORMAT.md §4.4).
A request thread encodes its entry and copies it into its shard's stage, holding a lock only
for that copy. One writer thread per shard swaps a stage out when it is full (32 KB of plain
entries) or 10 ms after its first entry, assigns the entries their Sequences, compresses them
as one zstd frame and places the frame as one batch, committed by one magic. Two stages per
shard let one fill while the other is placed.

This is what compression costs and buys:

* **The request path does not compress.** Compressing each entry on its own thread cost more
  than everything else the journal does, because zstd's cost was per flush, and a shared
  stream put it under the shard's monitor. A whole batch compresses in a fraction of that CPU
  and to a fraction of the bytes.
* **An entry is readable when its batch is placed**, not when its write returns: up to 10 ms
  later at low traffic, sooner as traffic fills stages.
* **A crash loses what is staged** (§8.1), at most two stages per shard.
* An entry larger than a stage is placed as a batch of its own, by the thread that wrote it,
  once everything staged before it is placed.

Without compression (`storage.compression: none`) there is nothing to batch for: each entry is
placed under the shard's monitor by the thread that wrote it, and committed when the write
returns.

## 4.1 Logical Commit

A journal entry is considered logically complete when:

* all its fragments are written and each one's CRC32C is valid
* the magic of its FULL or FIRST fragment, written last, is present
* END marker (in FlatBuffer event model) is present

This is independent of physical disk state.

---

## 4.2 Durability vs Commit

These are explicitly separated:

| Concept    | Meaning                                               |
| ---------- | ----------------------------------------------------- |
| Commit     | Entry is logically complete                           |
| Durability | Entry has reached persistent storage via OS writeback |

Commit MUST NOT imply durability.

---

# 5. Backpressure Model

## 5.1 Primary Rule

The system MUST apply backpressure instead of silent failure.

With compression, a request thread that finds both of its shard's stages full waits until the
writer thread hands one back. That wait is the journal's backpressure: the request is slowed,
never dropped, and nothing is written past what the writer can place.

---

## 5.2 Backpressure Triggers

Backpressure MUST be applied when:

* mapped region is full
* file cannot be extended
* OS returns ENOSPC
* ingestion queue exceeds configured bounds

---

## 5.3 Backpressure Behavior

When triggered, the system MUST:

* stop accepting new requests OR
* return explicit failure (e.g. 503)

The system MUST NOT:

* drop entries silently
* overwrite existing journal data

---

# 6. Memory Model

## 6.1 Page Cache Dependency

The system relies entirely on:

* kernel page cache
* dirty page tracking
* background writeback threads

The JVM MUST NOT act as a buffering layer for persistence.

---

## 6.2 GC Isolation Principle

The write path SHOULD:

* avoid allocation in hot path
* avoid object graph construction during ingestion
* use direct byte operations where possible

---

# 7. Segment Model

## 7.1 File Rotation

When the active segment reaches capacity:

* a new mmap file MUST be created
* previous segment becomes immutable

---

## 7.2 Segment Finalization

A segment is considered closed when:

* no further writes occur
* it is un-mapped or marked read-only

No additional metadata mutation is allowed after closure.

---

# 8. Failure Model

## 8.1 Application Crash

If the process crashes:

* all committed entries remain valid in memory or disk buffers
* last partial entry MAY be lost
* with compression, entries staged and not yet placed are lost: at most two stages per
  shard, 64 KB of plain entries, which at low traffic is the last 10 ms of entries. A graceful
  shutdown places them first
* recovery is deterministic via CRC + sequential scan

---

## 8.2 Kernel Panic / Power Loss

If the OS loses power:

* dirty page cache is lost
* last writeback window is undefined
* recovery begins from last consistent entry

This is an explicit tradeoff of mmap-based design.

---

## 8.3 Disk Full

If disk capacity is exhausted:

* writes MUST fail
* system MUST enter backpressure state
* ingestion MUST stop or reject traffic

Without compression the write that fails is the request's own, and that request fails. With
compression the batch is placed after its requests have completed, so they cannot be failed.
A full disk is then the system's failure, and the entries in flight are not the journal's to
save. The shard stops instead: it logs how many staged entries it could not place, refuses
every later entry so that those requests fail closed, and reports the failure through
`Journal.failure()`, which the gateway shows as an `ERROR` health problem (`/ready` returns
`503`) until it restarts. The gateway warns before this happens, when free space falls below
two more segments per shard.

---

## 8.4 Corruption

If bytes are corrupted:

* CRC32C MUST detect it (every fragment carries its own)
* corrupted entries MUST be skipped, and in a sealed segment the reader resumes at the next
  32 KB block boundary, so damage costs the rest of its block
* no repair is attempted in this layer

CRC32C is an integrity check, not an authenticity one, and the format does not need it to be
one. Fragments never cross a block boundary, so every boundary holds a header the writer put
there, and resynchronising never reads payload bytes as framing: a request or response body
crafted to look like entries is stepped over with the rest of its block, never delivered
(FORMAT.md §6.1).

---

# 9. Recovery Semantics

On restart:

The system MUST:

1. mmap last known segment(s)
2. scan sequentially from the end of the preamble
3. validate CRC32C per fragment, resuming at the next block boundary after damage
4. seal at the end of the last valid entry; an uncommitted entry at the tail is not damage
5. refuse a segment of another format version, setting it aside rather than deleting it

Recovery MUST be deterministic.

---

# 10. Non-Goals

This architecture explicitly does NOT provide:

* synchronous durability guarantees
* cross-node replication
* transactional consistency
* ordering across nodes
* guaranteed persistence under power loss

---

---

# 11. Reassembly Tuning

The reader that rebuilds exchanges (`ExchangeReassembler`) holds each exchange in memory
from its first event until its `EndExchange` arrives. Exchanges whose end never arrives
would accumulate without bound, so the reader ages them out. `ReassemblyOptions` controls
that, and every setting has consequences in both directions.

Defaults: `maxAge` 5 minutes, `maxInFlight` 250 000, `sweepIntervalEvents` 8192.

## 11.1 maxAge

**What it actually measures.** `maxAge` is *reader-side retention*, not request duration.
The clock starts when the reader first sees an event for an exchange, not when the request
began. This distinction matters:

* When **tailing a live stream**, the two are close, because events are read shortly after
  they are written. The relevant bound is not just how long the request takes, but how long
  until the reader sees the segment containing the end:

  ```
  maxAge > max request duration
         + segment rotation period
         + tailer tick interval
  ```

  (Compression used to add a term here. It is no longer part of a segment's life — a
  segment goes `.flux` → `.r7f` and stops there — so nothing sits between sealing and the
  tailer seeing the file.)

  An exchange that starts near the end of one segment and ends in the next is held for the
  whole of that gap.

* When **replaying archived segments**, wall-clock age is nearly meaningless: a reader
  consumes hours of journal in seconds, so almost nothing ages out and `maxAge` stops being
  a memory bound. `maxInFlight` is the effective limit during replay.

**Set too low.** Long-running but legitimate exchanges are abandoned while still in flight.
Each one then produces *two* misleading signals: an `onAbandoned` with no status, no end
timestamp and no traffic counters, followed later by an `onOrphanedEnd` when the real end
arrives. Body fragments arriving in between are reported as orphaned bodies. Slow uploads,
large downloads, long-polling and server-sent event streams are the usual victims. If a
deployment carries any of these, `maxAge` must exceed the gateway's longest permitted
request duration with margin, or the journal will systematically misreport its own
longest-lived traffic.

**Set too high.** Two costs:

* *Memory.* An in-flight exchange retains every body fragment journaled for it. On a live
  stream the steady-state cost is roughly `arrival rate x maxAge x journaled bytes per
  exchange`. At journal level FULL with large bodies this dominates the reader's heap.
* *Detection latency.* Exchanges that are genuinely lost — their end was in a segment that
  did not survive — are only reported after `maxAge`. Raising it directly delays the
  "entries missing" signal that makes loss visible.

## 11.2 maxInFlight

A backstop against heap exhaustion, not a tuning knob. When the tracked set reaches this
ceiling after an age sweep has already run, the reader evicts **everything currently
tracked**, including exchanges seconds old that would have completed normally. Those are
reported as `CAPACITY_EVICTED` and are indistinguishable, downstream, from genuine loss.

Reaching the ceiling should be treated as a misconfiguration rather than normal operation:
it means `maxAge` is too high for the arrival rate, or `maxInFlight` is too low for the
peak concurrency. Size it above peak concurrent in-flight exchanges with a healthy margin,
and alert on the log line rather than tuning it down to suppress it.

## 11.3 sweepIntervalEvents

Eviction is amortised over incoming events rather than performed per event, because the
sweep is a linear scan of the tracking table. Two consequences:

* An exchange can exceed `maxAge` by up to one sweep interval before it is evicted, so
  `maxAge` is a lower bound on retention, not an exact deadline.
* On a **quiet stream the sweep does not advance at all**, since it is driven by event
  arrival. `R7Tailer` therefore calls `sweep()` explicitly at the end of every tick, so the
  age limit is honoured even when no events were read. A consumer driving
  `ExchangeReassembler` directly must do the same, or abandoned exchanges will be held —
  and left unreported — until traffic resumes.

## 11.4 What the consumer sees

The two incomplete outcomes are reported separately because they carry different
information, and a logger should treat them differently:

| Callback            | End event seen | Exchange state                                            |
| ------------------- | -------------- | --------------------------------------------------------- |
| `onIncompleteEnd`   | yes            | status, timing, traffic counters and checksums all applied |
| `onAbandoned`       | no             | no status, no end timestamp, no counters; body truncated   |

An `onIncompleteEnd` record is terminal and complete as far as the journal goes — it can be
logged as a partial record. An `onAbandoned` record has absent fields that are *unknown*,
not zero, and a logger that writes them as zeros will produce an audit trail that quietly
lies about response status and duration.

## 11.5 Throwing from a consumer callback

A consumer that throws is **refusing** the record, and the reader treats it that way: it
rewinds to the start of the entry that produced the callback, reports the stall through
`JournalIntegrityListener.onDeliveryStalled`, and stops reading that segment. The entry is
offered again on the next tick, and nothing after it in that segment is read until it is
accepted. The segment is never marked processed and never deleted while it holds an entry
nobody received.

This is a deliberate trade. A sink that is briefly unavailable — a database restarting, a
queue full — costs latency and nothing else. The alternative, skipping the entry, would
checkpoint past a record nobody received and delete the only copy of it a tick later.

Two consequences for consumer code:

* **Throw for "not now", return for "not interesting".** A consumer that does not care about
  an exchange must return normally. Throwing to signal a filtering decision stalls the
  reader for ever.
* **Callbacks should be idempotent.** A refused entry is delivered again, so a consumer that
  fails *after* a side effect may see that side effect twice. `ExchangeReassembler` restores
  its own in-flight state on a refusal, so the retry carries the whole exchange rather than
  an orphaned end.

An exception thrown by `onAbandoned` during `sweep()` is outside the decoder's retry
mechanism. `ExchangeReassembler` catches and logs it because there is no journal entry to
rewind, so the abandoned exchange is not offered again.

## 11.6 Restarting a tailer

`R7Tailer` writes its checkpoint file on `shutdown()`, at once when a segment appears, is
finished or changes state, and otherwise at most once a second while only read positions
move. A restart, graceful or a hard kill, loses nothing:

* **Exchanges still being assembled are rebuilt.** The checkpoint file records, per shard,
  where the oldest open exchange began and the request ids of every open exchange (including
  one held for a consumer that refused it). On its first tick the tailer replays from there
  up to the checkpoint for those ids only, then reads on as usual, so they complete as if
  the tailer had never stopped. Nothing that was already delivered is delivered again by
  the replay.
* **Delivery is at least once.** Exchanges completed after the last write of the checkpoint
  file, which after a hard kill means within about the last second, are delivered again. Consumers
  must be idempotent, as §11.5 already requires.
* **The replay needs the segment each open exchange began in.** If retention deleted it in
  the meantime, the exchange cannot be rebuilt: the tailer logs how many were lost, and their
  remaining events arrive as orphans. Keep a reaper's `ttl` above the longest a tailer may be
  down plus `maxAge`.
* **`maxAge` restarts** for a rebuilt exchange, since its clock is reader-side (§11.1).

---

# 12. Text Encoding

## 12.1 The stored encoding

Header names, header values, attribute names, attribute values and start lines are stored
as **ISO-8859-1 (latin-1) bytes**, and read back the same way.

This matches the wire: HTTP/1.1 header field values are latin-1 by definition, so values
arriving from a client round-trip byte-for-byte. It also keeps the write path free of
encoding work — the writer copies each character's low byte directly into its scratch
buffer with no intermediate allocation.

Readers MUST decode these fields as ISO-8859-1. Decoding as UTF-8 or US-ASCII maps every
byte above 127 to U+FFFD, which silently rewrites the record rather than reproducing it.

## 12.2 The constraint this places on filters

A character outside latin-1 cannot be represented. The writer truncates each character to
its low byte, so `U+2013` (en dash) is stored as `0x13`, and the original value is
unrecoverable.

This cannot arise from client traffic. It can only arise when a filter or plugin sets a
header or attribute from an arbitrary Java string — a translated message, a name from a
database, a JSON field.

Therefore **values set programmatically are validated at the point of modification**.
Every `MutableGatewayHeaders` implementation rejects an out-of-range value on `set` and
`add`, throwing `InvalidTextValueException` with the field name and the index of the
offending character — including `MutableFastGatewayHeaders`, which is what filters
actually mutate at runtime. Validating only the standalone containers would leave the
guarantee true in tests and false in production. The mutable attribute containers are
covered the same way. A `null` name or value is refused the same way, reporting
`TextValues.ABSENT` as the index — a null would otherwise reach the journal writer and
fail there, far from the filter that set it. See `com.ethlo.r7.api.TextValues`.

Multi-value `set(name, Iterable)` validates every value into a local list before mutating
anything, so a rejected value leaves the container exactly as it was and a single-pass
source is iterated only once. An empty iterable removes the entry.

Rejecting there rather than at journal-write time is deliberate: the error names the
filter that produced the value instead of surfacing much later as mojibake in an audit
record, it keeps the check out of the journal write path, and it is consistent with the
gateway being fail-closed — a request whose metadata cannot be recorded faithfully is not
quietly recorded wrongly. The cost is a scan of each value a filter sets, on the request
path; header values are short and the scan allocates nothing.

Callers needing to carry arbitrary Unicode through an attribute should encode it
explicitly — percent-encoding or base64 — and decode it in the consumer. The journal
layer does not do this for them, because a silent re-encoding is indistinguishable from a
corrupted record when someone comes to read the audit trail.

---

# 13. Design Statement

The r7 journal mmap architecture is a single-node, append-only, OS-buffered log system where durability is delegated to the kernel and correctness is enforced via deterministic structural validation (CRC + framing), not synchronous persistence.
