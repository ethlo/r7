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
* maintain user-space write buffers for durability

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

# 4. Commit Semantics

## 4.1 Logical Commit

A journal entry is considered logically complete when:

* all required fields are written
* CRC32C is valid
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

---

## 8.4 Corruption

If bytes are corrupted:

* CRC32C MUST detect it
* corrupted entries MUST be skipped
* no repair is attempted in this layer

---

# 9. Recovery Semantics

On restart:

The system MUST:

1. mmap last known segment(s)
2. scan sequentially from last valid offset
3. validate CRC32C per entry
4. discard incomplete tail entry
5. resume ingestion at first invalid boundary

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
offending character — including `UndertowGatewayHeaders`, which is the view filters
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
