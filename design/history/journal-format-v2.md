# r7f format version 2: block framing (plan)

**Status:** done 2026-10-01 (#126 spec, #127 implementation, docs after). `FORMAT.md` is the
normative text; this note keeps the reasoning and what was given up for it.

## Why

`FORMAT.md` §6.1: after damage, the reader scans for the next Magic and trusts what parses
there. A request or response body can carry bytes that parse as a valid entry, so resync can
accept a forged one. CRC32C is not a MAC. Version 1 has no way to tell the two apart.

Version 2 removes the scan. Following the LevelDB/RocksDB log format, the segment is cut into
fixed blocks, and every block starts with a header the writer placed there. After damage, a
reader resumes at the next block boundary and only interprets bytes it reached from a boundary
through lengths it has verified. Payload bytes are never read as framing, so a forged entry
cannot be reached however it is built. That holds for any number of damaged regions and without
a seal record. No key, no MAC, and nothing to change on the gateway's hot path.

What it costs (decided 2026-10-01, see the session discussion):

- **Recovery granularity.** After damage, the rest of the damaged block is lost even where its
  entries are intact. That's up to 32 KB per damaged region.
- **Tailer copies.** An entry split across blocks is copied together before FlatBuffers decodes
  it: about 3% of header entries and about 25% of 8 KB body chunks at 32 KB blocks. The gateway
  is unaffected.
- **No compatibility with version 1.** Pre-release, so nothing needs it. A reader refuses any
  other version (§11) and sets the file aside rather than deleting it.

Compression was left out of this step, with the preamble's codec field reserved for it. It
followed as codec `1` (`FORMAT.md` §4.4): a zstd stream per block, on by default, after a
short measurement showed the journal 9–15x smaller on benchmark traffic with no measurable
latency at 1,000 req/s, against 17–40% of peak throughput at saturation.

## Format

**Blocks.** The file is divided into blocks of `Block Size` bytes, aligned to file offset 0.
The default is 32 KB, a constant, not a setting. It's recorded in the preamble, so every
segment describes itself. The segment size must be a multiple of it, and validation names
`shard_size` if not. Block 0 holds the 1024-byte preamble, and the first fragment starts at
1024.

**Preamble additions** (from the reserved area, offsets as in v1 otherwise):

| Offset | Field | Size | |
|---|---|---|---|
| 4 | Version | 2 | `2` |
| 50 | Block Size | 4 | power of two, 4 KB to 1 MB |
| 54 | Codec | 2 | `0` = none; any other value MUST be rejected by this version's readers |
| 56 | Reserved | 968 | zero |

File Magic stays `R7F1`, because it names the family; Version distinguishes them. The seal
record (22–49) is unchanged. Data End is one past the last fragment of the last entry.

**Fragment** (16-byte header, then data):

```
Magic    4   0x52374632 ('R7F2'); for FULL and FIRST, the commit: written last
Type     1   FULL=1, FIRST=2, MIDDLE=3, LAST=4
Flags    1   0; reserved for codec use
Reserved 2   0
Length   4   data bytes in this fragment, >= 1
CRC32C   4   over Type, Flags, Reserved, Length and the data
Data     Length
```

- **Entry content.** An entry's data is the concatenation of its fragments' data:
  `Sequence(4) FBLen(4) RawLen(4) FlatBufferPayload RawPayload`. PayloadLen and the per-entry
  CRC of v1 go: every byte is covered by its fragment's CRC.
- **Placement.** A fragment never crosses a block boundary. If fewer than 17 bytes (a header
  plus one data byte) remain in a block, the writer leaves them zero and continues at the next
  block. A reader skips to the next block on the same arithmetic, so padding is implied by
  position, never inferred from its content.
- **Commit.** For a multi-fragment entry, the writer writes every MIDDLE and LAST fragment
  completely (magic included), then the FIRST fragment's body, then a store-store fence, then
  the FIRST magic. A sequential reader stops at the zero FIRST magic and never reaches the
  continuations. FULL works exactly as a v1 entry does. This is invariant 1 unchanged: the
  magic is the commit.

**Reading.**

1. Start at 1024, or at a checkpoint, which is always a FULL or FIRST position.
2. At each position, if fewer than 17 bytes remain in the block, go to the next block. A zero
   Magic means end of data in an active segment, and a hole before Data End in a sealed one.
3. Verify the CRC, then follow the type: FULL is delivered; FIRST, MIDDLE… LAST are reassembled
   and delivered.
4. **Resync, sealed segments only** (an active segment still stalls as in v1 §6):
   - from the damage, go to the next block boundary;
   - a MIDDLE or LAST there belongs to an entry whose start was lost: verify it, skip it, and
     report it;
   - continue at the first FULL or FIRST;
   - a zero or a failing CRC at a boundary means go to the next boundary;
   - the region skipped is reported as now, and missing Sequences still show as a gap.
5. **Never scan for a Magic.** §6.1 becomes a closed property, with a test, instead of a
   documented gap.

## Code (where it lands)

- **`R7fConstants`:** `VERSION_2` (the current version), `FRAGMENT_MAGIC`, the fragment header
  size, the fragment types, `DEFAULT_BLOCK_SIZE`, and the bounds; preamble offsets for Block
  Size and Codec.
- **`R7fJournal.writeEntryLocked`:**
  - The logical entry is three sources: a 12-byte head, then the FlatBuffers buffer, then the
    raw payload. A helper copies a range of that logical stream into the segment and updates
    the fragment's CRC as it goes.
  - Size the whole entry, fragment overhead and padding included, before `ensureCapacity`.
  - Commit order as above.
  - No allocation: the per-thread `EntryEncoder` keeps whatever scratch this needs.
  - FaultAhead is unaffected.
- **`R7fRecoveryManager`:** `scan`, `validateEntryAt` and `findNextEntry` become fragment-aware
  and resync on block boundaries. A version-1 `.flux` is refused and reported, never deleted.
  A partial multi-fragment entry at the tail is uncommitted, the same as a zero magic.
- **`JournalDecoder`:**
  - Fragment walk, reassembly into a reusable buffer (allocated only when a split entry
    arrives, and kept), and block-boundary resync at both of today's resync sites.
  - `findNextEntry` (the magic scan) is deleted.
  - `DecodeStats` is unchanged.
  - A single-fragment entry stays a zero-copy slice.
- **`R7Tailer`:** checkpoints stay byte offsets, always at a FULL or FIRST position. Verify the
  legacy-offset handling still holds.
- **`JournalAnalyzer`:** check it against the new framing.
- **Server config:** `StorageConfig` validates `shard_size` as a multiple of the block size,
  with bounds derived from `R7fConstants`, naming `shard_size`.
- **Tests:**
  - Rewrite the hand-built fixtures in `JournalIntegrityTest`, `JournalInvariants` and
    `JournalLifecycleTest` for v2.
  - New: an entry spanning blocks; padding at a block tail; an entry larger than a block
    (MIDDLE fragments); a hole and a bit flip, each resynced at the next block; a damaged
    continuation at a boundary.
  - **The §6.1 test:** a payload that contains a complete, CRC-valid forged fragment, placed
    after damage, must never be delivered, while the genuine entries after the next boundary
    are.
  - Version 1 refused.
  - Each new rule gets its named test in `design/journal-invariants.md`.

## Steps (each its own PR, merged on green; docs-only ones merged at once)

1. **Spec**, docs only:
   - `FORMAT.md`: the v2 sections; §6 rewritten for block resync; §6.1 as a closed property;
     §11 gains version 2.
   - Link this note.
2. **Implementation:**
   - writer, decoder, recovery, tailer, analyzer, config validation;
   - the tests above;
   - `journal-invariants.md` updated with the new test names;
   - `./mvnw clean install` green;
   - `benchmark/run.sh --scenario journal --journal-levels HEADERS,FULL --repeat 3` against
     main, recorded in the PR, expected flat on the gateway side.
3. **Docs:**
   - the journal README;
   - any tailer README that describes the framing;
   - the performance tuning page, if anything moved.

## Out of scope

- Compression: the codec field is reserved.
- A user-facing `block_size` setting: the field is in the preamble, so it can come later.
- Reading version 1.
