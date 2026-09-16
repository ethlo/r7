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
| 22     | Seal Magic         | 4    | Zero until sealed; then `0x52374653` (`R7FS`)   |
| 26     | Entry Count        | 8    | Entries the segment holds; valid only when sealed |
| 34     | Last Sequence      | 4    | Highest entry Sequence; valid only when sealed  |
| 38     | Data End           | 8    | Offset one past the last entry; valid only when sealed |
| 46     | Reserved           | 978  | MUST be zero-filled                             |

The first entry MUST begin at offset 1024.

### The Seal Record

Offsets 22–45 are the **seal record**, and are zero while a segment is active. A writer
sealing a segment MUST write the Entry Count, Last Sequence and Data End first and the Seal
Magic last, with a store-store barrier between — the same commit discipline as an entry's Magic
(§5.1), applied at segment scale.

A reader MUST treat the Seal Magic's absence as "this segment was never sealed", and MUST
NOT read the other three fields in that case. A `.r7f` without it was renamed without being
sealed; its entries are still valid and SHOULD be read, but nothing about it can be
cross-checked, and the condition SHOULD be reported.

The point of the record is to make completeness checkable rather than inferred: a reader
that finishes a sealed segment compares the last Sequence it decoded against the recorded
one, and a discrepancy names exactly how many entries at the end were never read.

**Data End** is what a reader bounds itself by. Everything from Data End to the end of the
file is the unused remainder of the pre-allocation: not data, and not a hole. A reader MUST
NOT interpret those bytes, and MUST NOT require the file to have been truncated to Data End
— a sealed segment MAY keep its tail. A Data End beyond the end of the file means the
segment lost bytes after it was sealed, and MUST be reported; nothing inside the file could
reveal that.

Entry Count and Last Sequence are equal for a segment sealed by a healthy writer, because
Sequence starts at 1 and increases by one per entry. **Recovery is the exception**: it seals
what it could read, so a segment whose Entry Count is lower than its Last Sequence is one
that lost entries — visible from the preamble alone, without scanning the file.

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

## 5.1 The Magic Is the Commit

A writer MUST write an entry's Magic **last**, after the Sequence, the three length fields,
the payload and the CRC32C are all in place, and MUST issue a store-store barrier between
them. The field order on disk is unchanged (§4); this is a requirement on the order of the
stores, not on the layout.

The Magic is therefore the entry's commit record. A reader MUST treat its presence as the
guarantee that the rest of the entry is there, and its absence — a zero where a Magic
belongs — as the end of the committed data, not as damage. A reader sharing the mapping
with a live writer, which is the normal deployment, MUST pair the writer's barrier with an
acquire barrier after reading the Magic.

This is what lets a reader tail a segment that is being written without guessing. Stamping
the Magic first would make a half-written entry byte-for-byte indistinguishable from a
corrupt one, and every reader would need a heuristic to tell "not yet" from "never".

Two consequences follow, and implementations depend on both:

* A pre-allocated segment MUST be zero-filled, and a segment MUST NOT be reused, or a stale
  Magic could be mistaken for a commit.
* A Magic followed by an entry that does not parse is real damage, not a partial write.

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
  * In a **sealed** segment, before Data End (§3.2), it is not a tail but a region that
    never reached the device. The reader MUST scan past it for a later entry, and MUST
    report the region and the resulting Sequence gap. Treating it as end-of-data is what
    makes power-loss holes invisible, because an unwritten page reads back as zeroes and is
    indistinguishable from an unused tail by inspection of that byte alone. Data End is what
    tells the two apart; a reader that has no seal record to consult MUST fall back to
    treating the whole file as data, and SHOULD report that it could not be sure.
* A **forward jump** in Sequence means entries that were written are not present. The
  reader MUST NOT treat this as end of data, and MUST report the count of missing entries.
* A **backward step** in Sequence means the file is not a valid append-only segment and the
  reader MUST stop.
* An entry that **fails to parse** — bad framing, or a CRC that does not match — in a
  segment that still carries its pre-allocation is real damage, because §5.1 makes an
  unpublished entry read as a zero rather than as a broken one. The reader MUST NOT consume
  the remainder of such a segment on that basis: it MUST leave its position at the start of
  that entry and re-read from there. The segment belongs to its writer until it is sealed,
  and the damage MUST be reported once sealing makes the file final.

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
* MUST record Data End in the seal record when sealing a segment (§3.2), so that a reader
  can tell an unused tail from a hole (§6) without inspecting the file's size
* SHOULD NOT truncate a sealed segment to Data End, and MUST NOT do so while another
  process may have the file mapped. Data End makes truncation unnecessary, and a reader
  that has mapped the pre-allocated length will fault when the file shrinks underneath it.
  A sealed segment's unused tail is reclaimed by deleting the segment, not by shortening it

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
