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

> FlatBuffer schema: `com.ethlo.r7.vlf.fbs` (see r7fbs specification)

This document MUST NOT be used to interpret event payload structure.

---

# 2. Conventions

* MUST / SHOULD / MAY are as per RFC 2119.
* All integers MUST be big-endian.
* File is strictly append-only.

---

# 3. File Layout

## 3.1 Structure

A r7f file MUST have the following layout:

```
[1024-byte preamble][entry][entry]...[entry]
```

---

## 3.2 Preamble

The first 1024 bytes MUST be reserved as a file header:

| Field       | Size | Requirement                   |
| ----------- | ---- | ----------------------------- |
| File Magic  | 4    | MUST be `0x564C4631`          |
| Version     | 2    | MUST identify format version  |
| Sequence ID | 8    | MUST be monotonic per segment |
| Reserved    | 1010 | MUST be zero-filled           |

The first entry MUST begin at offset 1024.

---

# 4. Entry Format

Each entry MUST be encoded as:

```
Magic (4)
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

MUST equal `0x564C4631`.

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
  com.ethlo.r7.vlf.fbs
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

* PayloadLen
* FBLen
* RawLen
* FlatBufferPayload
* RawPayload (if present)

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

1. Scan entries sequentially
2. Validate CRC32C per entry
3. Skip corrupted entries
4. Treat entries as opaque byte records
5. Pass FlatBufferPayload to external decoder if needed

Replay semantics of `JournalEvent` are defined in the FlatBuffer specification, not here.

---

# 7. Determinism

Implementations:

* MUST NOT reorder entries
* MUST NOT modify existing entries
* MUST append only
* SHOULD rely on OS page cache for writeback

---

# 8. Failure Model

| Condition  | Behavior                                  |
| ---------- | ----------------------------------------- |
| Crash      | Tail entries may be incomplete            |
| Power loss | Last dirty pages may be lost              |
| Disk full  | Writes fail; ingestion must halt upstream |
| Corruption | Entry is dropped via CRC failure          |

---

# 9. Non-Goals

This specification explicitly does NOT define:

* Event schema (FlatBuffers)
* HTTP semantics
* Routing logic
* Retry behavior
* Distributed replication
* Indexing or query systems

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

# 11. Summary

r7f is:

* append-only binary log format
* framed by fixed-size preamble
* entry-based CRC32C integrity model
* schema-agnostic payload container
* replayable via sequential scan
