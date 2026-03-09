# R7 Journal Technical Specification

High-performance, sharded, append-only journal system using Memory-Mapped I/O (mmap) for HTTP audit logging. Optimized
for workloads exceeding 150k events/sec.

---

## 1. Architecture Overview

The system utilizes a decoupled **Producer/Consumer** model:

* **Journal (Producer):** Appends binary events to sharded memory-mapped segments.
* **Tailer (Consumer):** Periodically polls, sorts, and reassembles fragmented events into structured output.

---

## 2. Storage Format

[VLF format](FORMAT.md)