r7f Journal File Format

## Status of This Memo

This document specifies version 2 of the r7f journal file format used by r7 for
high-throughput event logging. Version 2 replaces version 1's self-delimiting entries with
block framing, so that resynchronisation after damage never interprets payload bytes as
framing (§6.1). The reasoning and the implementation plan are in
`design/journal-format-v2.md`.

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
* All integers in the preamble and fragment framing MUST be big-endian. (The FlatBuffer
  payload is little-endian, as defined by its own specification.)
* File is strictly append-only.
* An **entry** is one journal record. A **fragment** is a framed piece of an entry; an entry
  is stored as one fragment or as several (§4). A **block** is a fixed-size, aligned region
  of the file (§3.1).

---

# 3. File Layout

## 3.1 Structure

A r7f file MUST have the following layout:

```
[1024-byte preamble][fragment][fragment]...[fragment]
```

The file is divided into **blocks** of Block Size bytes (§3.2), aligned to file offset 0.
Block 0 begins with the preamble; the first fragment MUST begin at offset 1024. The file
size MUST be a multiple of Block Size.

A fragment MUST NOT cross a block boundary. Every block boundary at or before Data End is
therefore either the start of a fragment the writer placed there, or an unwritten zero. That
is the property resynchronisation rests on (§6).

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
| 4      | Version            | 2    | MUST identify format version; `2` for this spec |
| 6      | Segment Sequence   | 8    | MUST be monotonic per shard                     |
| 14     | Created (epoch ms) | 8    | Wall-clock creation time of the segment         |
| 22     | Seal Magic         | 4    | Zero until sealed; then `0x52374653` (`R7FS`)   |
| 26     | Entry Count        | 8    | Entries the segment holds; valid only when sealed |
| 34     | Last Sequence      | 4    | Highest entry Sequence; valid only when sealed  |
| 38     | Data End           | 8    | Offset one past the last fragment of the last entry; valid only when sealed |
| 46     | Seal Flags         | 4    | Bit flags; valid only when sealed. Zero for a healthy seal |
| 50     | Block Size         | 4    | Power of two, from 4096 to 1048576              |
| 54     | Codec              | 2    | `0` (none); any other value MUST be rejected    |
| 56     | Reserved           | 968  | MUST be zero-filled                             |

File Magic names the format family and is unchanged across versions; Version tells them
apart (§11).

**Block Size** is recorded per segment, so every segment describes itself and a reader needs
no configuration to read it. A reader MUST reject a segment whose Block Size is not a power
of two within the bounds above. A file size that is not a multiple of it is not a reason to
reject the segment: a writer never produces one, so it means the file lost bytes, and what
remains is read and the loss reported (§3.2, Data End).

**Codec** is reserved for per-block compression. This version defines only `0`, meaning
fragment data is stored as written. A reader MUST reject a segment with any other Codec
rather than misread compressed data as an entry.

### The Seal Record

Offsets 22–49 are the **seal record**, and are zero while a segment is active. A writer
sealing a segment MUST write the Entry Count, Last Sequence, Data End and Seal Flags first and
the Seal Magic last, with a store-store barrier between — the same commit discipline as an
entry's Magic (§5.1), applied at segment scale.

A reader MUST treat the Seal Magic's absence as "this segment was never sealed", and MUST
NOT read the other four fields in that case. A `.r7f` without it was renamed without being
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

**Seal Flags** carry what a reader could not otherwise learn, because Data End hides it.
Unknown bits MUST be ignored, so that a later version may define them.

| Bit  | Name     | Meaning                                                              |
| ---- | -------- | -------------------------------------------------------------------- |
| 0x1  | `RETAIN` | Content past Data End is readable but was deliberately not published |

A sealer MUST set `RETAIN` when it stops before the end of what it could read and leaves
readable entries behind — today, when its scan stops on a Sequence regression (§6). A reader
that finishes such a segment MUST NOT delete it, however clean its own read was: everything
the sealer withheld lies past Data End, so the reader never sees it and has nothing else to
go on. Without the flag, hiding those entries and then deleting the file would destroy them,
which is the opposite of why they were withheld.

The distinction the flag preserves is between damage and abandonment. Bytes lost to a hole
or a bad checksum are gone whoever looks at them, so a segment holding only those may be
deleted once read. Bytes after a regression are intact and still decodable — they are simply
not safe to replay — and deleting the segment is the only thing that would actually destroy
them.

**Segment Sequence** is a counter, not a clock. It MUST increase by one per segment within
a shard, and MUST NOT be derived from wall-clock time, which can step backwards under NTP
correction, VM migration or manual adjustment.

**Created** is wall-clock deliberately: it names a point in time that has to survive a
restart and mean something to a human reading a file name. Durations and ordering within a
process MUST NOT be derived from it; implementations SHOULD use a monotonic clock for
those.

---

# 4. Fragments and Entries

## 4.1 Fragment Format

Each fragment MUST be encoded as:

```
Magic (4)
Type (1)
Flags (1)
Reserved (2)
Length (4)
CRC32C (4)
Data (Length)
```

The 16 bytes before Data are the **fragment header**.

### Magic

MUST equal `0x52374632` (`R7F2`). For a FULL or FIRST fragment it is the entry's commit
record and is written last (§5.1).

### Type

| Value | Name   | Meaning                                  |
| ----- | ------ | ---------------------------------------- |
| 1     | FULL   | The whole entry                          |
| 2     | FIRST  | The first fragment of a split entry      |
| 3     | MIDDLE | An interior fragment of a split entry    |
| 4     | LAST   | The final fragment of a split entry      |

Any other value is damage.

### Flags

Reserved for codec use. With Codec `0` a writer MUST write zero and a reader MUST ignore it.

### Reserved

MUST be zero.

### Length

The number of Data bytes in this fragment. MUST be at least 1, and MUST NOT carry the
fragment past the end of its block.

### CRC32C

MUST be computed over Type, Flags, Reserved, Length and Data, in that order. A fragment
whose CRC does not match MUST be considered corrupted.

The Magic is NOT covered: it is the commit record, and a reader learns a fragment's
position from the framing that led to it, never by searching for the Magic (§6).

---

## 4.2 Entry Content

An entry's content is the concatenation of the Data of its fragments, in file order:

```
Sequence (4)
FBLen (4)
RawLen (4)
FlatBufferPayload (FBLen)
RawPayload (RawLen)
```

The content's length MUST equal `FBLen + RawLen + 12`. Every byte of it is covered by the
CRC of the fragment that carries it.

### Sequence

* MUST start at `1` for the first entry of a segment
* MUST increase by exactly one per entry within a segment
* MUST NOT be reused within a segment

The sequence number is what allows a reader to distinguish *end of data* from *missing
data*. See §6.

### FlatBufferPayload

* MUST contain a valid FlatBuffer-encoded `JournalEvent`
* MUST conform to external specification:

  ```
  com.ethlo.r7.r7f.fbs
  ```
* This RFC does NOT define or interpret its contents.

### RawPayload

* MAY be present (RawLen MAY be zero)
* If present, MUST follow FlatBufferPayload immediately
* MUST NOT be interpreted by the journal layer

---

## 4.3 Placement

A writer places an entry at the current position as follows.

1. If fewer than 17 bytes (a fragment header plus one Data byte) remain in the current
   block, the writer MUST leave them zero and move to the start of the next block. This is
   **padding**. A reader applies the same arithmetic and skips it by position alone; it
   never inspects padding to decide that it is padding.
2. If the whole entry fits in the rest of the block, the writer MUST store it as one FULL
   fragment.
3. Otherwise the writer MUST split it: a FIRST fragment that fills the rest of the current
   block, MIDDLE fragments that each fill a whole block, and a LAST fragment, each
   continuation starting at a block boundary. An entry that fits in the current block MUST
   NOT be split.

So a FIRST or MIDDLE fragment always ends exactly at a block boundary, and a MIDDLE or LAST
fragment always starts at one. A reader MUST treat a split entry that departs from this
shape as damage.

An entry, with its fragment headers and any padding before it, MUST fit in the remainder of
the segment; a writer that cannot place it there rotates to a new segment first. An entry
never spans segments.

---

# 5. Entry Semantics

The journal layer defines only **structural integrity**, not event meaning.

Specifically:

* Entries are opaque at the journal layer
* Ordering is defined strictly by file sequence
* No semantic interpretation of payload is permitted

## 5.1 The Magic Is the Commit

An entry is committed by the Magic of its FULL or FIRST fragment, and that Magic MUST be
written **last**:

* For a FULL fragment, the writer MUST write the rest of the fragment first, then issue a
  store-store barrier, then write the Magic.
* For a split entry, the writer MUST write every MIDDLE and LAST fragment completely, Magic
  included, then the FIRST fragment's header fields and Data, then issue a store-store
  barrier, then write the FIRST fragment's Magic.

The field order on disk is unchanged (§4.1); this is a requirement on the order of the
stores, not on the layout.

The Magic of a FULL or FIRST fragment is therefore the entry's commit record. A reader MUST
treat its presence as the guarantee that the rest of the entry — every continuation of a
split entry included — is there, and its absence — a zero where a Magic belongs — as the
end of the committed data, not as damage. A reader sharing the mapping with a live writer,
which is the normal deployment, MUST pair the writer's barrier with an acquire barrier after
reading the Magic.

This is what lets a reader tail a segment that is being written without guessing. Stamping
the Magic first would make a half-written entry byte-for-byte indistinguishable from a
corrupt one, and every reader would need a heuristic to tell "not yet" from "never". A
sequential reader stops at an uncommitted FIRST and so never reaches its continuations,
whose Magics are already in place.

Two consequences follow, and implementations depend on both:

* A pre-allocated segment MUST be zero-filled, and a segment MUST NOT be reused, or a stale
  Magic could be mistaken for a commit.
* A Magic followed by a fragment that does not parse is real damage, not a partial write.

---

# 6. Replay Model

A compliant reader MUST:

1. Start at offset 1024, or at a checkpoint, which MUST be the offset of a FULL or FIRST
   fragment the reader previously reached by the rules below
2. At each position, skip padding (§4.3), then read a fragment header
3. Validate CRC32C per fragment, and the placement rules of §4.3 for split entries
4. Deliver a FULL fragment's Data as an entry; reassemble FIRST, MIDDLE… LAST into one entry
   and deliver that
5. Reach the next position only through a verified Length and the padding arithmetic, or by
   resynchronising at a block boundary as described below — **never by scanning for a
   Magic**
6. Treat entries as opaque byte records, and pass FlatBufferPayload to an external decoder
   if needed

A compliant reader MUST additionally track the Sequence field and MUST report any
discontinuity:

* A **zero byte** where a Magic is expected means no fragment was ever written at that
  offset. What that implies depends on the segment:
  * In a segment that still carries its pre-allocation — the active one being written —
    this is the normal end of data and the reader stops.
  * In a **sealed** segment, before Data End (§3.2), it is not a tail but a region that
    never reached the device. The reader MUST resynchronise (below), and MUST report the
    region and the resulting Sequence gap. Treating it as end-of-data is what makes
    power-loss holes invisible, because an unwritten page reads back as zeroes and is
    indistinguishable from an unused tail by inspection of that byte alone. Data End is what
    tells the two apart; a reader that has no seal record to consult MUST fall back to
    treating the whole file as data, and SHOULD report that it could not be sure.
* A **forward jump** in Sequence means entries that were written are not present. The
  reader MUST NOT treat this as end of data, and MUST report the count of missing entries.
* A **backward step** in Sequence means the file is not a valid append-only segment and the
  reader MUST stop.
* A fragment that **fails to parse** — an unknown Type, a Length that crosses its block, a
  CRC that does not match, or a split entry that breaks §4.3 — in a segment that still
  carries its pre-allocation is real damage, because §5.1 makes an unpublished entry read
  as a zero rather than as a broken one. The reader MUST NOT consume the remainder of such a
  segment on that basis: it MUST leave its position at the start of that entry and re-read
  from there. The segment belongs to its writer until it is sealed, and the damage MUST be
  reported once sealing makes the file final.

  This is a requirement about progress, not about tolerance: consuming to the end of a
  pre-allocated segment tells a reader that checkpoints by offset that it has read the
  whole file, and every entry the writer appends afterwards is then skipped without a
  word.
* In a **sealed** segment, the same failure means the entry is lost: the reader MUST
  report it and resynchronise.
* An entry a **consumer refuses** — one whose framing and CRCs are sound but whose delivery
  the reader's own client rejects — MUST NOT be passed over. The reader MUST leave its
  position at the start of that entry and offer it again on a later pass, and MUST NOT
  advance any Sequence expectation past it. Nothing later in that segment is read until it
  is accepted.

  Skipping it looks harmless and is not: the reader checkpoints past a record nobody
  received, the segment then reads as fully processed, and a reader that deletes what it has
  finished destroys the only copy. A sink that was unavailable for one tick would cost an
  exchange, permanently. Stalling is loud, bounded by the consumer's own recovery, and
  destroys nothing.

  A reader that cannot distinguish a refusal from a payload it could not decode — lazy
  payload decoding makes both surface at the same call — MUST resolve the ambiguity toward
  refusal. A stall names the segment, offset and Sequence and can be diagnosed; the opposite
  mistake is silent and irreversible.

**Resynchronisation** happens only in a sealed segment, and only within Data End:

1. From the damaged position, the reader moves to the next block boundary. The rest of the
   damaged block is lost, even where entries in it are intact.
2. At the boundary, a zero Magic, an unknown Type, or a failing CRC means the reader moves
   to the next boundary.
3. A valid MIDDLE or LAST at the boundary belongs to an entry whose start was lost. The
   reader MUST skip it, following its Length, and report it.
4. The reader resumes at the first FULL or FIRST it reaches this way.

Everything skipped is reported as one region, and the entries lost in it show as a Sequence
gap at the next entry delivered.

Replay semantics of `JournalEvent` are defined in the FlatBuffer specification, not here.

## 6.1 Resynchronisation Never Reads Payload as Framing

A reader interprets bytes as a fragment header only at a position it reached from offset
1024, from a checkpoint, or from a block boundary, through Lengths whose CRCs it has
verified. By §3.1 and §4.3 every such position holds a header the writer placed there, or
zeroes: Data never begins at a block boundary, never ends past one, and is never reached
except by skipping it whole. Bytes that arrived as payload — a request or response body
carried in RawPayload, which this layer never inspects — are therefore never read as
framing, however they are crafted and wherever damage leaves the reader.

This closes the gap of version 1, whose reader resynchronised by scanning forward for the
next Magic and trusted whatever parsed there. A body crafted to contain a Magic followed by
self-consistent framing with a matching CRC was indistinguishable from a genuine entry once
damage put the scan inside it. CRC32C is still not a MAC, and version 2 does not make it
one: it does not need to, because no payload byte is ever in a position where a CRC is
checked as framing.

What this does not cover is an adversary who can write the segment file itself. Such a
writer can produce any framing it likes, and only authenticating the writer could defend
against it; that is outside this format. Accidental damage to a header that leaves a
matching CRC is the ordinary CRC32C residual, as for any other field.

A compliant implementation MUST have a test that places a complete, CRC-valid forged
fragment inside a payload after damage and shows that it is never delivered, while the
genuine entries after the next block boundary are.

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
| Process crash / SIGKILL | Page cache survives; at most the final entry is uncommitted    |
| Power loss / host reset | Unflushed pages are lost, anywhere in the file, not only at the tail |
| Disk full               | Writes fail; ingestion must halt upstream                      |
| Corruption              | The damaged block's remainder is dropped; the reader resumes at the next block boundary |

Because writeback is left to the OS (§7), the format makes **no durability guarantee under
power loss**. The kernel may write dirty pages in any order, so a segment can contain valid
entries after a region that never reached the device, and a split entry can lose its FIRST
fragment while its continuations survive. This is why Sequence (§4.2) is mandatory: the
format does not promise that data survives a power cut, but it does promise that a reader
can tell when data did not.

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
* Authenticity against a party that can write segment files (see §6.1)

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

| Version | Change |
| ------- | ------ |
| 1       | Initial format: self-delimiting entries, resynchronised by scanning for the Magic |
| 2       | Block framing: fragments that never cross a block boundary, a CRC per fragment, resynchronisation at block boundaries only (§6.1); Block Size and Codec in the preamble |

A reader MUST reject a file whose Version it does not recognise, rather than attempt to
interpret it, and MUST NOT delete such a file on the strength of having read nothing from
it. There is no compatibility path between versions; the format is small enough that a new
version means a new reader. Segments written by a previous version are drained by a reader
of that version before the writer is upgraded.

---

# 12. Summary

r7f is:

* append-only binary log format
* framed by fixed-size preamble and fixed-size blocks
* fragment-based CRC32C integrity model
* sequence-numbered, so loss is detectable
* resynchronised at block boundaries, so payload is never read as framing
* schema-agnostic payload container
* replayable via sequential scan
