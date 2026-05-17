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

# 11. Design Statement

The r7 journal mmap architecture is a single-node, append-only, OS-buffered log system where durability is delegated to the kernel and correctness is enforced via deterministic structural validation (CRC + framing), not synchronous persistence.
