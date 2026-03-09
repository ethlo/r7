# R7 Journal MMAP

R7 Journal MMAP is the append-only, commit-marked storage module of the R7 gateway. It provides deterministic, high-fidelity capture of streaming HTTP requests with bounded durability guarantees. It is designed as a foundational upstream ingest capture layer where silent data loss is unacceptable, while allowing streaming responses and unbounded request sizes.

---

## Design goals

### Guarantees

* No silent loss of committed journal entries
* Corruption detection (via CRC32C)
* Deterministic crash recovery
* Bounded, configurable durability window
* Backpressure instead of silent drop
* Single-pass assembly via scatter-gather offsets

---

### Limitations

R7 Journal does *not* guarantee:

* Synchronous durability before HTTP acknowledgment
* Protection against catastrophic disk destruction
* Infinite buffering under downstream outage
* Multi-node replication
* Byzantine fault tolerance
* Legal-grade tamper evidence

---

### Non-Goals

* Distributed message broker
* Transactional database
* Multi-node consensus system
* Permanent object store
---

## Intended Use Case

* High-throughput HTTP ingest capture layer
* Streaming request handling
* Auditability with deterministic replay
* Single-node operation acceptable
* Minimal latency

---

## Design

### 1. Append-Only Storage

All data is written sequentially to a memory-mapped journal. No in-place mutation occurs, ensuring:

* Minimal disk I/O
* Deterministic replay
* No partial overwrite corruption

### 2. Explicit Commit Semantics

Each request is represented by:

* `START` marker
* Zero or more `BODY` markers
* `END` marker

The `END` marker contains total byte count and a rolling CRC32C over all payload bytes. Only requests with a valid `END` marker are considered committed and eligible for downstream processing.

### 3. Deterministic Replay

On restart, R7 Journal:

* Scans the journal sequentially
* Rebuilds request state
* Ignores incomplete requests without `END`
* Verifies integrity via CRC

Replay is single-pass and deterministic.

### 4. Bounded Durability Window

Disk durability is controlled via a configurable `force()` policy:

* Per time interval
* Per byte threshold
* Optional per write (performance-intensive)

All data up to the last completed `force()` call is guaranteed durable. Data written after that point may be lost in a crash.

### 5. Backpressure Over Silent Loss

R7 Journal is designed to *not* silently drop requests. Under resource constraints:

* Disk full → new requests are rejected
* Journal size limit exceeded → ingestion stops
* Downstream unavailable → journal grows until limits hit

### 6. Streaming-Friendly

Supports fully streaming request and response bodies. No full request buffering is required. Note that HTTP success may be returned before durability is guaranteed.

---

## Durability Model

* `START` = request observed
* `END` = request logically committed
* `force()` boundary = durable region

All committed requests before the last durability boundary will survive crash. Incomplete or post-boundary data may be lost.

---


---

## Operational Requirements

* Reliable local storage (SSD recommended)
* Filesystem with write barriers enabled
* Disk capacity sized for worst-case downstream outage window
* Monitoring for journal growth, active requests, and disk usage
