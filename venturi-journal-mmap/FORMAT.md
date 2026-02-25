# VLF Journal Entry Format

The **Venturi Log Format (VLF)** is a binary write-ahead log format designed for zero-copy, high-throughput logging of HTTP request/response events, including structured FlatBuffer metadata and optional raw payloads.

## File Preamble (Header)

Every Venturi log segment (both `.active` and `.vlf` files) begins with a fixed-length **1024-byte** (1KB) file preamble. This header identifies the file type, dictates the parser version, ensures deterministic log replay ordering, and reserves significant space for future file-level metadata (such as compression dictionaries, routing labels, or encryption keys).

### Preamble Field Descriptions

| Field | Size (bytes) | Description |
| --- | --- | --- |
| **File Magic** | 4 | Fixed magic number `0x564C4631` (`VLF1`) identifying the file as a Venturi log segment. |
| **Version** | 2 | Format version (`0x0001`). Used to ensure parser compatibility. |
| **Sequence ID** | 8 | Monotonically increasing segment identifier for chronological ordering during crash recovery. |
| **Reserved** | 1010 | Zero-padded bytes reserved for future file-level metadata. |

### Byte Ranges

| Field | Start Offset | End Offset | Size |
| --- | --- | --- | --- |
| **File Magic** | 0 | 3 | 4 B |
| **Version** | 4 | 5 | 2 B |
| **Sequence ID** | 6 | 13 | 8 B |
| **Reserved** | 14 | 1023 | 1010 B |

> **Note:** The first journal entry begins immediately at offset `1024`. The entry-level magic numbers (`0x564C4631`) act as synchronization markers throughout the remainder of the file.

Each journal entry is self-contained and includes a CRC32C checksum for integrity verification.

## Entry Layout
```text
+-----------------+--------------------+---------------------+---------------------+---------------------+---------------------+-----------------+
| Magic (4 bytes) | Payload Len (4 B)  | FB Len (4 bytes)    | Raw Len (4 bytes)   | FlatBuffer Payload  | Raw Payload        | CRC32C (4 bytes)|
+-----------------+--------------------+---------------------+---------------------+---------------------+---------------------+-----------------+
```
Each journal entry is self-contained and includes a CRC32C checksum for integrity verification.

### Field Descriptions

| Field | Size (bytes) | Description |
| --- | --- | --- |
| **Magic** | 4 | Fixed magic number `0x564C4631` (`VLF1`) to identify the start of an entry. |
| **Payload Length** | 4 | Total length of the entry payload (FB Len + Raw Len + 2 × 4 bytes for lengths). |
| **FlatBuffer Length** | 4 | Number of bytes in the FlatBuffer section. |
| **Raw Length** | 4 | Number of bytes in the optional raw payload section. |
| **FlatBuffer Payload** | variable | FlatBuffer-encoded `JournalEvent` root table containing the `EventPayload` union. |
| **Raw Payload** | variable | Optional raw binary data, e.g., request or response body. Present if `Raw Length > 0`. |
| **CRC32C** | 4 | CRC32C checksum covering **Payload Length**, **FB Length**, **Raw Length**, **FlatBuffer Payload**, and **Raw Payload**. |

---

## Entry Example

```text
Magic:        V L F 1 -> 0x564C4631
Payload Len:  0x000001DC (476 bytes)
FB Len:       0x000000E4 (228 bytes)
Raw Len:      0x00000098 (152 bytes)
FlatBuffer:   <JournalEvent root encoded>
Raw Payload:  <raw body bytes>
CRC32C:       0x12345678

```
---

## Event Types in FlatBuffer Payload

All FlatBuffer payloads are encoded using **`JournalEvent`** as the root table. This table contains an `EventPayload` union that wraps one of the specific underlying event types. Using byte arrays (`[ubyte]`) avoids UTF-8 validation overhead on the hot path.

1. **StartEvent** – Marks the start of a request or response.
* `req_id` (byte array)
* `start_line` (byte array)
* `headers` (array of Header objects)


2. **BodyEvent** – Contains metadata about a request/response body chunk.
* `req_id` (byte array)
* `direction` (`REQUEST` / `RESPONSE`)
* `length` (uint32)


3. **EndEvent** – Marks the completion of a request or response.
* `req_id` (byte array)
* `timestamp` (long)
* `status` (int)
* `bytes_sent` (long)
* `bytes_received` (long)
* `duration` (long)
* `request_crc32c` (uint32) - Cumulative checksum of the request body
* `response_crc32c` (uint32) - Cumulative checksum of the response body



---

## Integrity

**CRC32C** is calculated over the following in order:

* Payload Length (4 bytes)
* FlatBuffer Length (4 bytes)
* Raw Length (4 bytes)
* FlatBuffer Payload
* Raw Payload (if present)

Any mismatch during decoding indicates data corruption.

---

## Notes

* **Alignment**: All integer fields are **big-endian**.
* **Raw payloads** follow immediately after FlatBuffer data; they are optional and can be zero-length.
* **Segment rotation**: When the active memory-mapped segment is full, a new segment file is created. Old segments are finalized as `.raw` files.
* **FlatBuffer Builder**: Each entry builds a FlatBuffer independently, ensuring zero-copy writes and compact storage.

---

This format allows:

* Efficient sequential logging
* Partial reads of large raw payloads without deserializing the FlatBuffer
* Integrity checking with minimal overhead

### Byte Ranges Example

| Field | Start Offset | End Offset | Size |
| --- | --- | --- | --- |
| **Magic** | 0 | 3 | 4 B |
| **Payload Length** | 4 | 7 | 4 B |
| **FlatBuffer Length** | 8 | 11 | 4 B |
| **Raw Length** | 12 | 15 | 4 B |
| **FlatBuffer Payload** | 16 | 16+FBLen-1 | FBLen |
| **Raw Payload** | 16+FBLen | 16+FBLen+RawLen-1 | RawLen |
| **CRC32C** | 16+FBLen+RawLen | 16+FBLen+RawLen+3 | 4 B |
