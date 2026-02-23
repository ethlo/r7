# Venturi Journal Technical Specification

High-performance, sharded, append-only journal system using Memory-Mapped I/O (mmap) for HTTP audit logging. Optimized
for workloads exceeding 150k events/sec.

---

## 1. Architecture Overview

The system utilizes a decoupled **Producer/Consumer** model:

* **Journal (Producer):** Appends binary events to sharded memory-mapped segments.
* **Tailer (Consumer):** Periodically polls, sorts, and reassembles fragmented events into structured output.

---

## 2. Storage Format

### 2.1 File Naming Convention

`shard-{shard_id}-{timestamp}-{rotation_count}.{status}`

* **Status `.active`:** Currently open for writes.
* **Status `.raw`:** Completed and immutable; ready for tailing/cleanup.

---

## 3. Wire Protocol

All data is Big-Endian. Files start with a 1-byte version marker.

| Event Type  | Marker | Payload Structure                                         |
|:------------|:-------|:----------------------------------------------------------|
| **VERSION** | `0x00` | `byte version`                                            |
| **BEGIN**   | `0x01` | `id_len(1), id(N), dir(4), line_len(4), line(N), headers` |
| **BODY**    | `0x02` | `id_len(1), id(N), dir(4), data_len(4), data(N)`          |
| **END**     | `0x03` | `id_len(1), id(N), timestamp(8), status(4), metrics(24)`  |

---

## 4. Operational Flow

### 4.1 Writing (Atomic Snapshots)

To ensure consistency during high-speed rotation:

1. Verify buffer capacity; trigger `rotateData()` if required.
2. **Snapshot** `currentFileId` and `buffer.position()`.
3. Write binary payload to mmap buffer.
4. Record captured `fileId` and `offset` into the Index.

### 4.2 Tailing (Strict Chronology)

The Tailer enforces order using a multi-level sort:

1. **Shard ID:** Isolate processing by partition.
2. **Timestamp:** Chronological progression.
3. **Rotation Count:** Sequential order within a timestamp.

### 4.3 Stateful Reassembly

The `ExchangeReassembler` persists an `in-flight` map across files.

* **Event Correlation:** Merged via `request_id`.
* **Boundary Resilience:** Handles requests spanning `shard-N` to `shard-N+1`.
* **Orphan Protection:** Events lacking a `BEGIN` marker are discarded.

---

## 5. Persistence & Reliability

### 5.1 Stable Checkpoints

Progress is tracked in `.venturi_checkpoints` using a **Stable Key** (filename minus extension).

* **Mapping:** `shard-0-177123-10` -> `offset`.
* **Stability:** Transitioning from `.active` to `.raw` does not reset the offset.

### 5.2 Memory Management

* **Direct Buffer Release:** Manual unmapping via `sun.misc.Unsafe` (requires JVM flag
  `--add-opens java.base/java.nio=ALL-UNNAMED`).
* **OS Page Cache:** Asynchronous flushing via `MappedByteBuffer.force()`.

---

## 6. Performance Characteristics

* **Throughput:** >175,000 req/s on standard hardware.
* **Concurrency:** Lock-free reads; synchronized partition writes.
* **I/O Model:** Zero-copy via mmap.