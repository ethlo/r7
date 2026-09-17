# The WARC sidecar: why, and what it has to carry over

Design agreed in conversation, not yet built. This is the next piece of work after the
journal hardening PR merged.

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
buffer and allocate per record, and it has not been through the native-image reachability work
— but the sidecar is a separate JVM process (`Dockerfile.tailer.jvm` already exists) and
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
misleading into a parser; binary bodies will wreck a terminal, so filter on `WARC-Type` first;
and per-record gzip is the WARC convention that takes greppability away, so keep the live file
uncompressed and let an archiver compress on rotation. That is the same position already taken
when compression left the gateway: it is a consumer's job, and its output belongs to that
consumer.