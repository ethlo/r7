# The WARC sidecar: why, and what it has to carry over

**Status:** built as `r7-tailer-warc` (`Dockerfile.tailer-warc.jvm`); the operator docs are in
`docs/journaling.md`. Two parts are not built. The WARC profile (`WARC.md`, under "Scope") does
not exist yet. "The index layer" and "MCP" are ideas, and by this document's own "Scope"
section they are not r7's to build: see `plans/README.md`. The one exception is the opt-in
per-file CDXJ index (`cdxj_index`), described at the end of "The index layer".

---

## Why

Rounds seven through eleven of that review found almost nothing about the file format and
almost everything about the **consumer contract**: who may throw, what gets counted, what a
failure is allowed to claim, what is pinned against eviction. The format settled around round
six; the machinery around it did not.

A consumer that reads *finished records* needs none of that machinery. No in-flight map, no
age sweep, no capacity ceiling, no abandoned exchanges, no orphaned ends, no delivery-stall
protocol, no redelivery pinning. Every High finding from rounds seven to eleven lived in those
mechanisms. They do not disappear — they move into the sidecar, which is the one place they
have to exist, written once instead of re-implemented by every consumer.

The second reason is operational. r7f needs a bespoke tool to answer "did this request get
journaled?". WARC is text headers with a blank-line terminator, so `grep`, `tail -F`,
`less +F` and the entire archiving ecosystem (`warcio`, `jwarc`, `warcat`, replay tooling)
work on it. That is a real capability at 3am, not an aesthetic preference.

**r7f is effective short-term storage, not a long-term archival format, and that's by
design.** It's the closest thing to Java object serialization this system has — fast to
produce, tightly coupled to the exact reader that produced it, and readable only by code that
tracks its framing and its FlatBuffers schema precisely. That coupling is a fair trade for a
format whose entire job is being written and read within minutes by processes on the same
machine, sharing the same schema version. It stops being a fair trade the moment the horizon
is years and the reader might not even be this codebase anymore — at that point the same
properties that make r7f cheap to write become exactly what make it brittle to keep: any drift
in the decoder, the schema, or the framing between when a segment was written and when someone
needs it back is a real risk to data nobody can outsource to a third-party tool the way a WARC
reader can be. A **compressed long-term archive built directly from r7f segments** — copy the
sealed `.r7f` bytes, compress, keep — was considered for exactly that reason (it would be
smaller than WARC; no per-record envelope, no verbose text headers) and rejected: it would
sit next to WARC claiming to also be "the archive," reintroducing the same bespoke-format
lock-in this whole document exists to get away from, just with compression bolted on. WARC is
the only thing this design calls the archive of record. r7f's job ends once WARC — or any
other sidecar — has read it.

### Other formats considered

- **HAR** — widest existing tooling, but a single JSON document, not an append/rotate/seal
  stream; no second-hop record; no truncation/digest convention. A trace-export format, not a
  continuous archival one.
- **PCAP** — wrong layer: packets, not reconstructed HTTP, and the gateway already did the
  TLS/reassembly work a packet capture would force every reader to redo.
- **mbox/MIME multipart** — same concatenated-text shape as WARC, but none of WARC's archiving
  conventions (digest, truncation, concurrent linking); adopting it means reinventing them.
- **Publishing r7f's schema openly** — doesn't buy interoperability; the point was an
  *existing* ecosystem of readers, not merely a non-secret one.

---

## Shape

```
gateway ──.flux/.r7f──▶ sidecar ──.warc──▶ consumers (ClickHouse, archive, tail -F)
```

The sidecar runs an `R7Tailer`, reassembles complete exchanges, and writes them as WARC. The
gateway is unchanged. Everything downstream of the `.warc` files is somebody else's problem,
deliberately.

### A reader stops and retries, exactly as it does today

`Content-Length` is the commit boundary: a record is publishable only when
`available >= headerEnd + contentLength + 4`. A partially written header block is even easier —
the absence of `\r\n\r\n` is unambiguous. This is *better* than r7f, because an appended file
has no pre-allocated zero tail, so the three-way ambiguity at an offset (committed / mid-write /
lost page) collapses to two.

### Three things must come along or properties are lost

1. **A sequence number in a custom field** (`WARC-X-R7-Sequence` or similar). Without it a
   file that loses a page in the middle reads back as a shorter, wholly self-consistent file —
   the exact failure entry sequences exist to catch, and the one nothing else detects.
2. **An open/sealed extension pair**: `.warc.open` → `.warc`, same protocol as `.flux` →
   `.r7f`. Without it, a record torn by power loss means a reader waits forever for bytes that
   will never arrive. Sealing is what says "stop waiting and report".
3. **A resync rule**: scan for `\r\nWARC/1.0\r\n` at a line boundary. Weaker than magic plus
   CRC — a body can contain that sequence — so it must be paired with the sequence field, and
   written into the spec rather than discovered later.

The `.open`/`.warc` pair is **deliberately outside the WARC spec**. The bytes stay spec-valid
at every moment; only the name changes. Extend around the format, never inside it.

---

## Config

Reuse `ValidatableConfig` / `ValidationResult`. Do not invent a mechanism.

- **Rollover: size or age, whichever first.** Size alone means a quiet deployment holds one
  unsealed file for weeks, and everything downstream keys off sealed files. Age alone fails
  under load. Suggested starting point: 128 MB / 15 minutes — seal latency is a feature here.
- **Roll between records, never inside one.** The size is a ceiling you cross, not one you
  respect. An exchange larger than the limit gets its own oversized file; write that down.
- **Refuse at startup** a rollover size that cannot hold a single record — same check as
  `R7fJournalProvider.MIN_SEGMENT_SIZE`, and the same reason: a livelock discovered in
  production is worse than a refusal at boot.
- **Naming mirrors the journal**: shard, sequence, created, time bounds appended on seal.
  Sequence is the identity; timestamps are decoration. Wall clock still steps.
- **A body cap**, with `WARC-Truncated: length` when it bites. This is also where the
  data-governance policy lives — a cap is auditable, "we mostly don't log big things" is not.
- **Record shape: four records per exchange**, linked by `WARC-Concurrent-To`. See below.

### Record shape: four records, each body stored once

An earlier draft of this document said two records per exchange, `request` and `response`.
That was wrong for what r7 is. The difference between what a client sent and what the gateway
forwarded — and between what the upstream returned and what the client got back — is the
evidence the tool exists to produce. Collapsing the exchange to two records throws it away, or
demotes it to an annotation on the record that survived.

So four records, in the order they occurred:

| # | `WARC-Type` | message | payload |
| - | ----------- | ------- | ------- |
| 1 | `request`   | client to gateway     | request body |
| 2 | `request`   | gateway to upstream   | not stored |
| 3 | `response`  | upstream to gateway   | not stored |
| 4 | `response`  | gateway to client     | response body |

All four carry `WARC-Concurrent-To` naming the others: the field is explicitly permitted on
`request` and `response`, and is the one WARC field that may be repeated within a record.

**Bodies are stored once because r7 does not modify them.** It is a streaming proxy — payloads
are not buffered or rewritten — and the journal already commits to that, holding one
`RequestBody` and one `ResponseBody` per exchange rather than one per hop. If that ever stopped
being true the journal would be wrong before this mapping was. Both recorded bodies are
client-side, which is where r7 actually observes them, and which is why they attach to records
1 and 4.

**Records 2 and 3 say outright that their payload is not there.** A bodyless `request` record
does not mean "no payload was stored", it means "this message had no body", and a replay tool
would act on the difference. Each therefore carries:

- `WARC-Truncated`, which is what the format has for a payload not stored in full. The core
  reasons are `length`, `time`, `disconnect` and `unspecified`, and the spec allows others to
  be defined by extension.
- `WARC-Payload-Digest` of the body that was not stored. This is not a stretch of the field:
  it is defined as the digest "of the payload referred to **or** contained by the record", so a
  digest of a payload held elsewhere is within its plain meaning.

Together with `WARC-Concurrent-To`, that is enough for a reader to resolve the payload without
r7-specific knowledge: among the records of this capture event, the body is the one whose
payload digest matches.

**`WARC-Refers-To` is not used here, and cannot be.** It is the obvious-looking field for "my
content is over there", and the spec forbids it precisely on the record types we need it on:
it *shall not* be used in `warcinfo`, `response`, `resource`, `request` or `continuation`
records. It is available only on `metadata`, `revisit` and `conversion`.

**Two alternatives were considered and rejected.**

*`revisit` records* for 2 and 3. Mechanically this fits — the `identical-payload-digest`
profile exists for exactly "same payload, recorded separately", such a record may have no
block at all, and `WARC-Refers-To` is permitted there. But `revisit` means the revisitation of
a URI whose content has not changed since it was archived, and the second hop of a single
exchange is not that. It would also change `WARC-Type`, so a tool scanning for the requests in
a capture would not find the forwarded one — losing the record we added it for.

*Two records plus a `metadata` record* carrying the hop deltas. Cleanest for third-party tools,
and `metadata` is defined for content that describes another record. Rejected because it makes
the forwarded request an annotation rather than a message that happened, which is the same
collapse in a different shape.

### One capability the gateway does not have

The sidecar is not on the request path, so **fsync before the rename to `.warc`**. The
argument that killed fsync in the journal does not apply, and these files are the archive of
record. "Sealed" meaning durable rather than merely renamed is strictly stronger than anything
r7f offers, and it is free here.

---

## Implementation

**jwarc**, on the JVM. Two constraints that normally rule out a library do not apply: it will
buffer and allocate per record — but the sidecar is a separate JVM process (`Dockerfile.tailer.jvm` already exists) and
buffering is exactly its job. That separation is what earns the right to use a normal library.

Use **WARC 1.1**: 1.0 rounds away the sub-second precision the gateway keeps.

Spec details that bite hand-rolled writers:

- `Content-Length` on a `response` record covers the whole HTTP block — status line, headers
  and body — not just the entity.
- `WARC-Block-Digest` and `WARC-Payload-Digest` are different things; conflating them makes
  every consumer's integrity check wrong.
- `WARC-Record-ID` must be a URI, conventionally `urn:uuid:`.
- A `warcinfo` record at the head of each file is the conventional home for the profile
  version, so a consumer can tell which `WARC-X-R7-*` fields to expect.

Verify against jwarc's own docs that arbitrary header names can be set for the custom fields.

---

## The index layer

WARC is a linear log; nothing is indexed. "All 404s this week" means reading every byte.

The archiving world's answer is CDX/CDXJ — a sorted line-oriented sidecar index with a byte
locator per record. **ClickHouse replaces that**, and `ClickHouseJsonEachRowWriter` in
`r7-tailer-jsonld` is already most of the ingest path.

One row per exchange: timestamp, status, method, path, route, upstream, the three durations,
byte counts, request id — **plus the WARC locator: filename, offset, length**. Those locator
columns are the whole trick. Without them you have metrics and no evidence, which is the
failure mode of every access-log-to-OLAP pipeline: you can see that 4% of `/orders` 404'd at
14:00 and cannot see a single one of them.

**Invariant: ClickHouse is a derived index and never the archive.** Losable, rebuildable by
replaying the WARCs. The moment a row there is the only copy of an exchange, a network service
is part of the audit guarantee and the fail-closed story has a hole in it.

Prefer a *second* consumer reading finished WARCs over having the sidecar write both: slower,
but self-healing, and it keeps the sidecar's job down to one thing.

### The per-file CDXJ index

`cdxj_index: true` makes the tailer write a sorted CDXJ index beside each WARC file. It is the
standard sidecar the archiving world already reads (pywb, OutbackCDX), so it needs no consumer
of r7's own, and it keeps to the rule above in spirit: it is built by reading the finished
file back (jwarc's CDXJ formatter, `CdxjIndex`), never from what the writer remembers, so it is
derived, rebuildable, and identical for a file sealed normally or after a crash. It is sealed
before its WARC file, so a sealed `.warc.zst` always has one.

One line per exchange, for the client response (or its `revisit`): the record a replay should
serve. The upstream response is recognised by being followed directly by the client response
that names it in `WARC-Concurrent-To`, and skipped. Non-GET keys get pywb's
`__wb_method=<method>`; pywb's `__wb_post_data` is left out on purpose, because it copies up to
4 KB of request body into a file that travels further than the archive.

A sealed index arrives up to `max_file_age` late, which is too late for the live locator
columns described above. `cdxj_stdout: true` prints each exchange's line as its records are
written, computed by the same code from the uncompressed records and the offsets they are about
to land at, so the live line and the sealed one are the same. The `request_id` field in every
line is the join key to the JSON tailer's row. Feeding that into ClickHouse is a log shipper's
job, which keeps the loader out of r7 as "Scope" says.

### MCP

ClickHouse ships an MCP server, which gives `search` for free. It does not give `fetch` —
pulling record N from a WARC at a byte offset. That is a ~30-line HTTP endpoint on the sidecar
(`GET /record?file=…&offset=…&len=…`), useful to humans and `curl` too.

The two-tool split is the point: search returns bounded locators, fetch returns one record.
Without it an MCP tool over an archive either blows the context window or times out.

Bodies not being in ClickHouse solves the governance problem structurally — the MCP server
cannot leak a request body because the column does not exist, and `fetch` becomes the single
chokepoint where body-access policy lives. Point it at a read-only user with grants on one
table.

---

## Scope

The ClickHouse loader does **not** belong in the r7 umbrella. WARC readers are everywhere and
ClickHouse ingest is a commodity; the specific combination is probably ~100 lines of glue
rather than something to install, which is exactly why it is not r7's problem.

What *does* belong in the umbrella is the **WARC profile**: a `WARC.md` beside `FORMAT.md`
specifying which `WARC-X-R7-*` headers exist, what the sequence field means, how request and
response records are paired, the open/sealed extensions, and which fields are guaranteed
present at which `JournalLevel`. If consumers are external, the file is the contract. Without
that document every consumer invents its own mapping and the benefit of choosing a standard
format is gone.

**Deliverables for the next PR:** the sidecar, `WARC.md`, and the two-phase naming. Nothing
else.

---

## Testing

One thing to build from day one: a **round-trip against a different implementation**. Write
with jwarc, read back with `warcio` or `warcat` in CI. Self-consistency proves nothing about
interoperability, and interoperability is the entire reason for choosing WARC over the format
we already own.

## Latency, since it was the open question

Two tick intervals end to end. The added hop is one tick, not a reassembly wait, because a
record can only be written once the exchange is complete — which is already when `onComplete`
fires today. Well inside "see live traffic".

`tail -F` (follow the *name*, so it survives rotation) gives a human the live view. Caveats:
`tail` does not respect record boundaries, so it will show half a record — fine for eyes,
misleading into a parser; binary bodies will wreck a terminal, so filter on `WARC-Type` first.

**Compression ended up deviating from the position above, on purpose.** "Keep the live file
uncompressed, let a downstream consumer compress on rotation" was the same precedent r7f
already set — `journal-invariants.md`: "Compression is not a phase... its output belongs to
that consumer, not this directory" — applied here by analogy, and it was the right default to
start from. The implementation departed from it anyway: it compresses per record, live, as
independent Zstandard frames — `.warc.zst`, following the (proposed) IIPC "Zstandard
Compression for WARC Files 1.0" convention, each record exactly one self-contained frame with
its own content size and checksum, concatenated like `.warc.gz` has done per-record since
WARC/1.0. The reason it's worth deviating here where it wasn't for r7f: compressing a whole
rotated file after the fact would cost the two properties per-record framing keeps — a tool
can still decompress-and-concatenate the whole file to recover a plain WARC file, and — given
an external offset index — a single record can be picked out and decompressed without
touching the rest of the file. Whole-file compression on rotation loses per-record random
access entirely; `grep` on the live file is lost either way (compression is compression), but
`zstdcat | grep` or an offset-aware tool still works record-by-record only with this shape.

**No shared dictionary, deliberately.** Per-record frames with an empty dictionary leave real
compression on the table — this format's small records (~1-2 KB) are mostly WARC-envelope
boilerplate and repeated HTTP header names, exactly what a trained dictionary is good at, and
a shared dictionary would likely buy another ~2x on top of what independent frames achieve.
Rejected anyway: a dictionary is external state the archive would depend on forever — every
reader, at any future date, needs the *exact matching dictionary version* alongside the file
to decode anything. That converts "the archive of record" into "the archive of record, provided
this specific dictionary blob wasn't lost or mismatched" — real, permanent risk for what's
supposed to be self-contained evidence, in exchange for a one-time storage win on bytes that
were already modest (headers/metadata-only WARC output for typical web traffic runs in the
low hundreds of MB per 100k exchanges, uncompressed; zstd without a dictionary already gets
this to roughly a third of that). Same principle invariant #4 in `journal-invariants.md`
argues for elsewhere: a decision that makes the archive unreadable without something outside
the file itself is a cost paid by every future reader, not just the one who saved the disk
space today.