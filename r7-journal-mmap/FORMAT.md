r7f Journal File Format

## Status of This Memo

This document specifies the r7f journal file format used by r7 for high-throughput event logging.

It defines only:

* file layout
* entry framing
* integrity model
* replay constraints

It does **not define event schemas**.

---

# 1. Scope

The r7f format is a **binary append-only file format** for storing serialized journal events.

Event encoding is delegated to an external specification:

> FlatBuffer schema: `com.ethlo.r7.r7f.fbs` (see r7fbs specification)

This document MUST NOT be used to interpret event payload structure.

---

# 2. Conventions

* MUST / SHOULD / MAY are as per RFC 2119.
* All integers in the preamble and entry framing MUST be big-endian. (The FlatBuffer
  payload is little-endian, as defined by its own specification.)
* File is strictly append-only.

---

# 3. File Layout

## 3.1 Structure

A r7f file MUST have the following layout:

```
[1024-byte preamble][entry][entry]...[entry]
```

A journal directory MAY also hold files that are not segments and carry no journal data.
A writer keeps its per-shard segment-sequence high-water mark in `shard-<shardId>.seq`,
so that the counter does not restart when retention has deleted every segment for that
shard; readers MUST ignore any file that is not a segment. These files are an
implementation's own bookkeeping and are not part of this format.

---

## 3.2 Preamble

The first 1024 bytes MUST be reserved as a file header:

| Offset | Field              | Size | Requirement                                     |
| ------ | ------------------ | ---- | ----------------------------------------------- |
| 0      | File Magic         | 4    | MUST be `0x52374631` (`R7F1`)                   |
| 4      | Version            | 2    | MUST identify format version; `1` for this spec |
| 6      | Segment Sequence   | 8    | MUST be monotonic per shard                     |
| 14     | Created (epoch ms) | 8    | Wall-clock creation time of the segment         |
| 22     | Reserved           | 1002 | MUST be zero-filled                             |

The first entry MUST begin at offset 1024.

**Segment Sequence** is a counter, not a clock. It MUST increase by one per segment within
a shard, and MUST NOT be derived from wall-clock time, which can step backwards under NTP
correction, VM migration or manual adjustment.

**Created** is wall-clock deliberately: it names a point in time that has to survive a
restart and mean something to a human reading a file name. Durations and ordering within a
process MUST NOT be derived from it; implementations SHOULD use a monotonic clock for
those.

---

# 4. Entry Format

Each entry MUST be encoded as:

```
Magic (4)
Sequence (4)
PayloadLen (4)
FBLen (4)
RawLen (4)
FlatBufferPayload (FBLen)
RawPayload (RawLen)
CRC32C (4)
```

---

## 4.1 Field Definitions

### Magic

MUST equal `0x52374631`.

---

### Sequence

* MUST start at `1` for the first entry of a segment
* MUST increase by exactly one per entry within a segment
* MUST NOT be reused within a segment

The sequence number is what allows a reader to distinguish *end of data* from *missing
data*. See §6.

---

### PayloadLen

MUST equal:

```
FBLen + RawLen + 8
```

---

### FlatBufferPayload

* MUST contain a valid FlatBuffer-encoded `JournalEvent`
* MUST conform to external specification:

  ```
  com.ethlo.r7.r7f.fbs
  ```
* This RFC does NOT define or interpret its contents.

---

### RawPayload

* MAY be present
* If present, MUST follow FlatBufferPayload immediately
* MUST NOT be interpreted by the journal layer

---

### CRC32C

CRC MUST be computed over:

* Sequence
* PayloadLen
* FBLen
* RawLen
* FlatBufferPayload
* RawPayload (if present)

The Magic is NOT covered by the CRC; it is the resynchronisation marker used to find entry
boundaries in a damaged file.

Entries with invalid CRC MUST be considered corrupted.

---

# 5. Entry Semantics

The journal layer defines only **structural integrity**, not event meaning.

Specifically:

* Entries are opaque at the journal layer
* Ordering is defined strictly by file sequence
* No semantic interpretation of payload is permitted

---

# 6. Replay Model

A compliant reader MUST:

1. Scan entries sequentially from offset 1024
2. Validate CRC32C per entry
3. Skip corrupted entries, resynchronising on the next Magic
4. Treat entries as opaque byte records
5. Pass FlatBufferPayload to external decoder if needed

A compliant reader MUST additionally track the Sequence field and MUST report any
discontinuity:

* A **zero byte** where a Magic is expected means no entry was ever written at that
  offset. What that implies depends on the segment:
  * In a segment that still carries its pre-allocation — the active one being written —
    this is the normal end of data and the reader stops.
  * In a **sealed** segment, which MUST be truncated to its exact size (§7), it is not a
    tail but a region that never reached the device. The reader MUST scan past it for a
    later entry, and MUST report the region and the resulting Sequence gap. Stopping here
    is what makes power-loss holes invisible, because an unwritten page in a pre-allocated
    file reads back as zeroes and is indistinguishable from an unused tail by inspection
    of that byte alone.
* A **forward jump** in Sequence means entries that were written are not present. The
  reader MUST NOT treat this as end of data, and MUST report the count of missing entries.
* A **backward step** in Sequence means the file is not a valid append-only segment and the
  reader MUST stop.
* An entry that **fails to parse** — bad framing, or a CRC that does not match — in a
  segment that still carries its pre-allocation MAY simply be an entry the writer has not
  finished publishing. A writer stamps the Magic before the payload and the CRC32C last
  (§4), so an entry caught mid-write is byte-for-byte indistinguishable from a damaged one.
  A reader MUST NOT consume the remainder of such a segment on that basis: it MUST leave
  its position at the start of that entry and re-read from there. The entry is either
  complete by the next read, or still incomplete when the segment is sealed — at which
  point it is final, §7 applies, and the damage MUST be reported as for any sealed segment.

  This is a requirement about progress, not about tolerance: consuming to the end of a
  pre-allocated segment tells a reader that checkpoints by offset that it has read the
  whole file, and every entry the writer appends afterwards is then skipped without a
  word.

Replay semantics of `JournalEvent` are defined in the FlatBuffer specification, not here.

---

# 7. Determinism

Implementations:

* MUST NOT reorder entries
* MUST NOT modify existing entries
* MUST append only
* SHOULD rely on OS page cache for writeback
* MUST truncate a segment to its exact size when sealing it, so that a reader can tell an
  unwritten tail from a hole (§6)

---

# 8. Failure Model

| Condition               | Behavior                                                       |
| ----------------------- | -------------------------------------------------------------- |
| Process crash / SIGKILL | Page cache survives; at most the final entry is torn            |
| Power loss / host reset | Unflushed pages are lost, anywhere in the file, not only at the tail |
| Disk full               | Writes fail; ingestion must halt upstream                      |
| Corruption              | Entry is dropped via CRC failure; reader resynchronises         |

Because writeback is left to the OS (§7), the format makes **no durability guarantee under
power loss**. The kernel may write dirty pages in any order, so a segment can contain valid
entries after a region that never reached the device. This is why Sequence (§4.1) is
mandatory: the format does not promise that data survives a power cut, but it does promise
that a reader can tell when data did not.

---

# 9. Non-Goals

This specification explicitly does NOT define:

* Event schema (FlatBuffers)
* HTTP semantics
* Routing logic
* Retry behavior
* Distributed replication
* Indexing or query systems
* Durability under power loss (see §8)

---

# 10. Design Intent

r7f is intentionally minimal:

* It defines **how bytes are stored**
* It does NOT define **what the bytes mean**

The FlatBuffer schema is intentionally external to preserve:

* independent evolution
* versioning decoupling
* multi-consumer compatibility

---

# 11. Version History

| Version | Change          |
| ------- | --------------- |
| 1       | Initial format  |

A reader MUST reject a file whose Version it does not recognise, rather than attempt to
interpret it. There is no compatibility path between versions; the format is small enough
that a new version means a new reader.

---

# 12. Summary

r7f is:

* append-only binary log format
* framed by fixed-size preamble
* entry-based CRC32C integrity model
* sequence-numbered, so loss is detectable
* schema-agnostic payload container
* replayable via sequential scan
