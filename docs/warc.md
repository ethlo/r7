# The WARC profile

The tailer's WARC output is standard [WARC 1.1](https://iipc.github.io/warc-specifications/specifications/warc-format/warc-1.1/),
so any WARC reader can open it. This page is the contract for what r7 puts in those files on top
of the standard: which records an exchange becomes, the `WARC-R7-*` fields, and the rules a
reader can rely on. It describes profile version **1**, which every file states in its
`warcinfo` record. How to run the tailer and configure the output is in
[Journaling](journaling.md#3-the-tailer).

## Files

| | |
| --- | --- |
| Name | `<file_prefix>-<millis>-<uuid>.warc.zst`, where `<millis>` is when the file was opened |
| While written | `<name>.open`. Never read a `.open` file: it is renamed once finished, and a file left `.open` by a crash is cut back to its last complete exchange and renamed on the tailer's next start |
| Sealed | Renamed to `<name>` only after its contents are flushed to disk. A sealed file never changes again |
| Compression | Each record is one independent [Zstandard](https://facebook.github.io/zstd/) frame with its content size and checksum, and the frames are concatenated. `zstd -d` turns a file into a plain WARC file; a reader with an offset can decompress one record without touching the rest |
| Index | With `cdxj_index: true`, a sorted `<file_prefix>-<millis>-<uuid>.cdxj` beside it, sealed before the WARC file is. See [CDXJ index](journaling.md#3-the-tailer) |

A file rolls over between exchanges, never inside one: the records of one exchange are always
in the same file, and an exchange larger than `max_file_size` makes that file larger than the
limit.

## The `warcinfo` record

Every file starts with a `warcinfo` record, `Content-Type: application/warc-fields`:

```text
software: ethlo-r7-tailer-warc
format: WARC File Format 1.1
conformsTo: https://iipc.github.io/warc-specifications/specifications/warc-format/warc-1.1/
r7-profile: 1
```

`r7-profile` is the version of this page the file follows. A reader that finds a version it does
not know should stop rather than guess. Every other record names the file's `warcinfo` in
`WARC-Warcinfo-ID`.

## One exchange, up to four records

An exchange becomes up to four records, written together and in the order the messages
happened:

| # | `WARC-Type` | Message | `WARC-Target-URI` | Holds the body |
| - | --- | --- | --- | --- |
| 1 | `request` | client to r7 | as the client asked | the request body |
| 2 | `request` | r7 to the upstream | as r7 forwarded it | no, see below |
| 3 | `response` | upstream to r7 | as r7 forwarded it | no, see below |
| 4 | `response` | r7 to the client | as the client asked | the response body |

- A record is written for each message whose start line was journaled. A route with journal
  level `NONE` in a direction has no records in it; an exchange with nothing journaled has none
  at all.
- Records 2 and 3 exist only for an exchange that was proxied. A request that a filter answered
  itself (a rate limit, a redirect) has records 1 and 4.
- Every record of an exchange names all the others in `WARC-Concurrent-To`, and carries the
  same `WARC-R7-Request-Id`.
- An incomplete exchange (the gateway stopped before it finished, or it was evicted while being
  reassembled) gets no records. Its JSON line still says what is known about it.
- With `exchanges: with_body`, only exchanges with a captured request or response body get
  records; with `exchanges: all`, every exchange does.

Each record's block is an `application/http` message: the start line and headers as journaled,
a blank line, and the body when this record stores it. Header values are ISO-8859-1, as in the
journal; redacted values appear as their fingerprints (see
[Redacted header and query parameter values](journaling.md#redacted-header-and-query-parameter-values)).

r7 streams bodies without changing them, so the body r7 forwarded is the body the client sent,
and the body the client got is the body the upstream returned. Each is stored once: the request
body in record 1, the response body in record 4.

### Where the body is

`WARC-Payload-Digest` is always the digest of the message's body, wherever the bytes are. A
record says plainly when it does not hold them:

| Case | Record | Fields |
| --- | --- | --- |
| The body is in this record | `request` / `response` | `WARC-Payload-Digest` |
| Records 2 and 3: the body is in record 1 or 4 of the same exchange | `request` / `response` | `WARC-Truncated: unspecified`, `WARC-Payload-Digest` |
| `warc.bodies: false`: the body was captured, but this output leaves it out | `request` / `response` | `WARC-Truncated: unspecified`, `WARC-Payload-Digest`. The JSON line holds the body when `json.bodies` is `true` |
| A body crossed the wire, but the journal level was below `FULL` | `request` / `response` | `WARC-Truncated: unspecified`, no digest |
| The journaled body failed its checksum when read back | `request` / `response` | `WARC-Truncated: unspecified`, `WARC-R7-Checksum-Mismatch: true`, no digest. The damaged bytes are never archived |
| The same body is already stored by an earlier exchange | `revisit` | The WARC 1.1 `identical-payload-digest` profile: `WARC-Refers-To`, `WARC-Refers-To-Target-URI`, `WARC-Refers-To-Date`, `WARC-Truncated: length` |
| No body | `request` / `response` | No digest, no `WARC-Truncated` |

A record without `WARC-Truncated` holds its message whole. To find a body held elsewhere in the
same exchange, take the `WARC-Concurrent-To` record with the same `WARC-Payload-Digest`.

Deduplication across exchanges remembers the last `dedup_cache_entries` digests per tailer
process. A body seen again after that, or after a restart, is stored again in full; a `revisit`
always points at an earlier record written by the same tailer.

## Fields

Standard fields with r7-specific values:

| Field | Value |
| --- | --- |
| `WARC-Date` | When r7 began capturing that message, to the precision the journal recorded: the client request when it arrived, the forwarded request when r7 started sending it, both responses when the upstream's first byte arrived (or, for a response r7 produced itself, when the request arrived). Not when the tailer wrote the record, so it matches the exchange's JSON line |
| `WARC-Target-URI` | `http://` + the request's `Host` header + the request target. r7 does not journal whether the client connection used TLS, so the scheme is always `http`. Without a `Host` header, `urn:r7:request:<request id>` |
| `WARC-Payload-Digest` | `sha256:` and the base32 SHA-256 of the body |
| `WARC-Record-ID` | `urn:uuid:` and a random UUID |

The r7 fields. A reader that does not know them can ignore them, as WARC requires:

| Field | On | Meaning |
| --- | --- | --- |
| `WARC-R7-Request-Id` | every exchange record | The exchange's request id, the same as `request_id` in its JSON line |
| `WARC-R7-Client-IP` | records 1 and 4 | The client's address as r7 resolved it (see `trusted_proxies`). `WARC-IP-Address` is not used for this: it means the server a record was fetched from |
| `WARC-R7-Sequence` | every record | The record's number within its file: 0 for the `warcinfo` record, then one more for each record, with no gaps |
| `WARC-R7-Group-End` | the last record of each exchange, and the `warcinfo` record | `true`. The records from just after one group end up to the next one belong together |
| `WARC-R7-Checksum-Mismatch` | a record whose body failed its checksum | `true` |

## What a reader can rely on

- **A gap in `WARC-R7-Sequence` means records are missing.** A file that lost data in the
  middle can otherwise read as a shorter file that looks whole. A reader that cares about
  completeness checks the sequence.
- **Groups are whole.** A sealed file never ends partway through an exchange, and an exchange
  is never split across files.
- **Each exchange is archived once.** If the tailer fails partway through an exchange, it takes
  the records back before trying again. After a crash, the exchange is written again only if
  its records did not survive.
- **The JSON line points here.** With both outputs on, an exchange's JSON line carries
  `warc: {file, offset, length}`, the sealed file name and the byte range of the exchange's
  records. Decompress from `offset` to read them. See [The JSON line](journaling.md#the-json-line).

## Compatibility

Within a profile version, new optional fields may be added; a reader must ignore fields it does
not know. Removing or renaming a field, or changing what one means, takes a new version.

Files written before profile 1 have no `r7-profile` line and name two fields
`WARC-X-R7-Sequence` and `WARC-X-R7-Group-End`. Their records otherwise follow this page.
