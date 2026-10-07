# Journal Tailing and Observability

r7 writes request/response traffic straight to memory-mapped binary files (journals) on disk
instead of serializing logs to text or making network calls on the request thread — that's what
keeps the hot path fast. To get that data into Grafana, ELK, ClickHouse or a WARC archive, you
run a **Tailer**: a separate process/sidecar that reads the journals asynchronously.

```mermaid
graph LR
    Client -->|HTTP| R7[r7 gateway Container]
    R7 -->|Binary Write| Vol[(Shared Volume: /journals)]
    Vol -->|Binary Read| Tailer[r7 Tailer Container]
    Tailer -->|JSON / Batch Insert| Ops[Observability Stack]

```

## Quick start

The common setup: the gateway journals headers and bodies, the tailer writes the metadata and
headers as JSON lines to its standard output, and the bodies go to WARC files. The tailer needs
no configuration for this; its defaults are exactly that.

**1. Journal the route in full.** In `routes.yaml`:

```yaml
routes:
  - id: my-route
    match:
      - PathPrefix:
          prefix: /api
    upstream:
      targets:
        - url: http://backend:8080
    journal:
      request:
        level: FULL
      response:
        level: FULL
```

**2. Run the gateway and the tailer on one volume.** The gateway writes the journals, the tailer
reads them. Add the two services to the compose file that runs your upstream (`backend` in the
route above):

```yaml title="docker-compose.yaml"
services:
  r7-api:
    image: ghcr.io/ethlo/r7-gateway:latest
    ports:
      - "8888:8888"
    volumes:
      - ./config:/app/config:ro
      - r7-journals:/journals:rw

  r7-tailer:
    image: ghcr.io/ethlo/r7-tailer:latest
    volumes:
      - r7-journals:/journals:ro
      - r7-warc:/warc:rw
      - r7-tailer-checkpoints:/checkpoints:rw

volumes:
  r7-journals:
  r7-warc:
  r7-tailer-checkpoints:
```

**3. Read the result.** Each exchange is one JSON line on the tailer's standard output, which
Promtail, Fluent Bit, Vector or your Docker logging driver picks up. The line holds the timing,
status, sizes and the headers of every leg (values not on the
[whitelist](#redacted-header-and-query-parameter-values) appear as fingerprints), and, for a
complete exchange with a body, a `warc` pointer to its records in `/warc`. The line is one physical line; this one is abbreviated and
pretty-printed for reading. Every field is listed under [The JSON line](#the-json-line):

```json
{
  "request_id": "01JA0Q6R8X4V2N7M3K5T9B1C0D",
  "route_id": "items",
  "start": "2026-10-01T12:00:00.000120Z",
  "duration": 0.004290,
  "client_request": {"method": "POST", "path": "/items", "headers": {"host": ["api.example.com"]}, "body_bytes": 212},
  "client_response": {"status": 201, "headers": {"content-type": ["application/json"]}, "body_bytes": 1274},
  "warc": {"file": "r7-1759320000000-6f1c….warc.zst", "offset": 48211, "length": 1873}
}
```

The `/warc` files are [WARC 1.1](https://iipc.github.io/warc-specifications/specifications/warc-format/warc-1.1/)
archives with each record in its own Zstandard frame: `zstd -d` turns a `.warc.zst` file into plain WARC
for any WARC tool. Journals grow until something deletes them: before sustained use, add the reaper from
[Retention](#4-retention-the-reaper). Everything else on this page is reference for tuning this
setup: the journal levels, redaction, every tailer property, other output layouts and retention.

## 1. Journal levels and redaction

Tailers only see what the gateway actually writes. Set the level per route (and optionally
override it by response status) in `routes.yaml`:

```yaml
routes:
  - id: my-route
    match:
      - PathPrefix:
          prefix: /api
    upstream:
      targets:
        - url: http://backend:8080
    journal:
      request:
        level: METADATA
      response:
        level: METADATA
        status_overrides:
          5xx: HEADERS   # more detail when things break
```

An override can raise a direction to `HEADERS`, but not to `FULL` unless the base level is already `FULL`: bodies are captured as they stream, and a lower base level installs no capture for an override to switch on. Such a configuration is refused at startup.

`NONE` / `METADATA` / `HEADERS` / `FULL` — see [Configuration §7](config.md#7-journaling-storage)
for the full table and the `storage` block (`work_dir`, `shard_size`, `shard_count`) that
controls where and how those journals are written on disk.

Route ids and upstream target URLs are recorded in every exchange's journal attributes
(`gateway.route.id`, `gateway.target`; the JSON line has them as `route_id` and
`upstream_request.targets`), and the journal stores ISO-8859-1 text only. A route
id or target URL with a character outside ISO-8859-1 is refused at startup, naming the field.

### Redacted header and query parameter values

At `HEADERS` and `FULL`, a header whose name is not on the `journal_security` whitelist is
journaled with a fingerprint in place of its value. Query parameter values in the request lines
get the same treatment at every level, unless the parameter is on `safe_query_parameters`
(see [Configuration: query parameters](config.md#query-parameters)). There are two forms:

| Form | Example | When |
| --- | --- | --- |
| `id:sha256:` + 6 hex digits | `id:sha256:3f2a91` | default: an unkeyed, truncated SHA-256 of the value |
| `id:hmac:` + 16 hex digits | `id:hmac:9c04e1d27b55a0f3` | `storage.journal_security.fingerprint_key` is set: HMAC-SHA-256 under that key |

Both let you see that two requests carried the same value. Only the keyed form hides the value
from someone who can read the journal. With the unkeyed form, a reader can hash a guess and
compare, so a low-entropy secret such as `Authorization: Basic` with a known user name and a
common password, a short API key or a `role=admin` cookie can be found with a dictionary.
Set a key in production:

```yaml title="server.yaml"
storage:
  journal_security:
    fingerprint_key: ${R7_FINGERPRINT_KEY}   # at least 32 characters, e.g. `openssl rand -base64 48`
```

Keep the key out of anything a journal reader can see. Changing it changes every fingerprint
from then on, so values journaled before and after the change no longer correlate. The
unkeyed form is still the default so that existing consumers that match on `id:sha256:` keep
working until you opt in. The key applies to journaled header and query parameter values only: the
`gateway.auth.basic.user` attribute and the management endpoint's summaries still use the
unkeyed form.

## 2. Mount the shared volume

Gateway and tailer share the `journal_dir` volume: the gateway writes, the tailer only reads.

!!! warning "Mount `journal_dir` read-only on the tailer"
    The tailer never deletes a segment; it only reads. Deciding when a fully read segment can
    be removed is the reaper's job (`ghcr.io/ethlo/r7-reaper`, see
    [§4 below](#4-retention-the-reaper)), and the reaper is the only container that needs write
    access to `journal_dir`. The tailer keeps its progress in its own `checkpoint_dir`, which
    the reaper reads to learn which segments the tailer is done with.

---

## 3. The tailer

**Image:** `ghcr.io/ethlo/r7-tailer:latest`

The tailer reads the journals once and writes each exchange to one or both of two outputs:

- **WARC files**: [WARC 1.1](https://iipc.github.io/warc-specifications/specifications/warc-format/warc-1.1/)
  records, the standard web-archiving format used by the Internet Archive and national
  libraries, in rotating `.warc.zst` files (one independent Zstandard frame per record, so
  `zstd -d` alone decompresses a file back to plain WARC). This is the archive: durable,
  replayable and readable by any WARC tool, such as [pywb](https://github.com/webrecorder/pywb),
  for compliance archiving, incident forensics or replaying traffic against a new backend.
- **JSON lines**: one JSON object per exchange, to standard output for Promtail (Grafana Loki),
  Fluent Bit, Vector or a Docker logging driver, or to rotating files. This is the log.

With both on, a body is stored once, in the WARC file: the JSON line carries a `warc` pointer to
the exchange's records instead of the bodies. A body the WARC output does not store (one of an
incomplete exchange, or any body with `warc.bodies: false`) goes into the JSON line when `json.bodies` is `true`. With both
`bodies` settings `false`, no body is stored anywhere; the records and lines still say there was
one, and which, by its size and checksums.

If writing the JSON line fails, the exchange's WARC records are taken back before the tailer
retries it, so a retry never archives an exchange twice.

**Configuration:** like the gateway, the tailer reads a YAML file: `tailer.yaml` in the working
directory by default (`/app/config` in the image), or the path in the `TAILER_CONFIG`
environment variable. It supports the same `${VAR:default}` interpolation as
`routes.yaml`/`server.yaml`. Without a file, every field takes its default, so a bare container
tails `/journals` to standard output as JSON lines without bodies, and archives the exchanges
that have a captured body in WARC files under `/warc`.

| Field            | Default        | Meaning |
|------------------|----------------|---------|
| `journal_dir`    | `/journals`    | Directory the tailer reads binary journals from |
| `checkpoint_dir` | `/checkpoints` | Directory `.r7_checkpoints` is read from and written to; a volume of its own, outside `journal_dir` (mounted `:ro`) and the outputs, since the reaper reads it and has no business in the archive |
| `poll_interval`  | `1s`           | The longest the tailer waits between reads. It wakes as soon as the gateway commits an entry (the gateway bumps a counter in `shard-<id>.ctl` beside the segments), so on the same host an exchange is read within microseconds to about a millisecond; this interval only matters where that file is not shared, such as across hosts. Supports `ms`, `s`, `m`, `h`, `d` |
| `warc`           |                | The WARC output; see below |
| `json`           |                | The JSON output; see below |

At least one output must be enabled.

**`warc`**

| Field                 | Default  | Meaning |
|-----------------------|----------|---------|
| `enabled`             | `true`   | Write WARC files |
| `exchanges`           | `with_body` | Which exchanges get records. `with_body` archives only exchanges with a captured request or response body; `all` archives every exchange, so every JSON line points into the WARC files. Whether the records hold the bodies is `bodies` |
| `output_dir`          | `/warc`  | Directory the files are written to. A file is written as `.warc.zst.open` and renamed once finished; one left `.open` by a crash is cut back to its last complete exchange and sealed on the next start |
| `file_prefix`         | `r7`     | Filename prefix |
| `max_file_size`       | `1gb`    | Roll to a new file once the current one reaches this size (at least `64kb`). Supports `b`, `kb`, `mb`, `gb` |
| `max_file_age`        | `15m`    | Roll to a new file once the current one is this old, even with little traffic. Supports `ms`, `s`, `m`, `h`, `d` |
| `zstd_level`          | `9`      | Zstandard compression level (1-22), applied per record |
| `dedup_cache_entries` | `100000` | How many payload digests are remembered for deduplicating identical payloads across exchanges |
| `cdxj_index`          | `false`  | Also write a sorted [CDXJ](https://specs.webrecorder.net/cdxj/0.1.0/) index next to each file; see below |
| `bodies`              | `true`   | Store captured bodies in the records. `false` writes the headers with `WARC-Truncated: unspecified` and the body's `WARC-Payload-Digest`, and leaves the body to the JSON line when that output is on with `bodies: true`; otherwise the body is not stored |

Each exchange becomes up to four linked records (client request, upstream request, upstream
response, client response); a payload already archived by an earlier exchange is written as a
WARC `revisit` record; and a checksum mismatch on read is marked rather than silently archived.
See [`design/warc.md`](https://github.com/ethlo/r7/blob/main/design/warc.md) for the record
shapes and why.

**CDXJ index.** With `cdxj_index: true`, every sealed `r7-….warc.zst` gets an `r7-….cdxj` beside it: one line per exchange, sorted by SURT key and timestamp, holding the URL, status, mime type, payload digest and the file name, offset and length of the client response record. This is the index format pywb and OutbackCDX read, so a lookup by URL and time goes straight to the record without scanning the archive. The index is built from the finished WARC file and renamed into place before the WARC file is, so a sealed `.warc.zst` always has its index; one sealed after a crash is indexed on the next start. Requests other than `GET` carry `__wb_method=<method>` in their key, as pywb does; request bodies are never copied into the index.

**`json`**

| Field               | Default  | Meaning |
|---------------------|----------|---------|
| `enabled`           | `true`   | Write JSON lines |
| `output`            | `stdout` | `stdout`, or `file` for rotating files. Each file is written as `<file_prefix>-<millis>-<uuid>.jsonl.open` and renamed to `.jsonl` once finished, so a shipper that picks up `*.jsonl` never reads one still being written. A file left `.open` by a crash is sealed on the next start, minus a torn last line, whose record is written again |
| `output_dir`        | `/json`  | With `output: file`: directory the files are written to |
| `file_prefix`       | `r7`     | With `output: file`: filename prefix |
| `max_file_size`     | `256mb`  | With `output: file`: roll to a new file once the current one reaches this size (at least `64kb`). Supports `b`, `kb`, `mb`, `gb` |
| `max_file_age`      | `15m`    | With `output: file`: roll to a new file once the current one is this old, even with little traffic. Supports `ms`, `s`, `m`, `h`, `d` |
| `bodies`            | `false`  | Write captured bodies into a line when no WARC record stores them, base64 encoded. Off, payloads stay out of the log; `body_bytes` and the checksums still show there was a body |

### The JSON line

The output is [JSON Lines](https://jsonlines.org/): one compact JSON object per exchange, one
object per line, UTF-8. A proxied `GET` with headers journaled, archived in a WARC file under
`exchanges: all`, looks like this (pretty-printed here):

```json
{
  "request_id": "01JA0Q6R8X4V2N7M3K5T9B1C0D",
  "route_id": "items",
  "start": "2026-10-01T12:00:00.000120Z",
  "end": "2026-10-01T12:00:00.004410Z",
  "duration": 0.004290,
  "remote_address": "10.0.0.7",
  "remote_address_source": "SOCKET",
  "client_request": {
    "level": "HEADERS", "method": "GET", "path": "/items", "query": "page=id:sha256:d4735e", "protocol": "HTTP/1.1",
    "headers": {"host": ["api.example.com"]}, "header_bytes": 142, "body_bytes": 0
  },
  "upstream_request": {
    "level": "HEADERS", "method": "GET", "path": "/v1/items", "query": "page=id:sha256:d4735e", "protocol": "HTTP/1.1",
    "targets": ["http://backend:8080"], "headers": {"host": ["backend:8080"]}
  },
  "upstream_response": {
    "level": "HEADERS", "protocol": "HTTP/1.1", "status": 200, "reason": "OK",
    "headers": {"content-type": ["application/json"]},
    "start": "2026-10-01T12:00:00.000300Z", "first_byte": "2026-10-01T12:00:00.004100Z",
    "end": "2026-10-01T12:00:00.004380Z", "duration": 0.004080
  },
  "client_response": {
    "level": "HEADERS", "protocol": "HTTP/1.1", "status": 200, "reason": "OK",
    "headers": {"content-type": ["application/json"]}, "header_bytes": 98, "body_bytes": 1274
  },
  "warc": {"file": "r7-1759320000000-6f1c….warc.zst", "offset": 48211, "length": 1873}
}
```

The rules every field follows:

- A field with no value is left out, never written as `null`. Without an end event
  (`incomplete` is `TIMED_OUT`, `CAPACITY_EVICTED` or `SHUTDOWN`), timing, status and sizes are
  unknown, and are left out rather than written as zero.
- Timestamps are RFC 3339 in UTC with microseconds. Durations are seconds, as a number with six
  decimals.
- Header and attribute values are always arrays of strings, in the order they were sent or set,
  so a header sent once and one sent twice have the same type. Values outside the
  [safe lists](#redacted-header-and-query-parameter-values) are fingerprints.
- Enumerated values are upper case.

Fields of the exchange:

| Field | Type | Present |
|---|---|---|
| `request_id` | string | Always |
| `incomplete` | string | Only when the record is not a complete exchange: `NO_START_EVENT`, `NO_STATUS`, `TIMED_OUT`, `CAPACITY_EVICTED` or `SHUTDOWN` |
| `route_id` | string | With an end event, when the exchange matched a route; unrouted requests that are journaled have `<unrouted>` |
| `start` | timestamp | With an end event: when the gateway received the request |
| `end` | timestamp | With an end event: when the response to the client finished |
| `duration` | number | With an end event: `end` minus `start`, in seconds |
| `remote_address` | string | When known: the client address |
| `remote_address_source` | string | With `remote_address`: `SOCKET`, `X_FORWARDED_FOR`, `X_REAL_IP` or `UNKNOWN` |
| `client_request` | object | Always: the request from the client |
| `upstream_request` | object | When the request was proxied: the request to the upstream |
| `upstream_response` | object | When the request was proxied: the upstream's response |
| `client_response` | object | Always: the response to the client |
| `attributes` | object of string arrays | With an end event, when the gateway recorded attributes other than the route and targets, such as `gateway.fallback.id`, `gateway.unrouted.reason`, `gateway.shortcircuit.name` and `gateway.auth.basic.user` |
| `warc` | object | When a WARC file holds the exchange |
| `warc.file` | string | The sealed WARC file's name |
| `warc.offset` | number | Byte offset of the exchange's first record in that file |
| `warc.length` | number | Compressed length of all the exchange's records: read `length` bytes at `offset` and decompress them with `zstd -d` |

Fields of the four leg objects (`client_request`, `upstream_request`, `upstream_response`, `client_response`):

| Field | Type | Legs | Present |
|---|---|---|---|
| `level` | string | all | Always: the journal level of that leg, `NONE`, `METADATA`, `HEADERS` or `FULL` |
| `method` | string | requests | When the request line was journaled |
| `path` | string | requests | When the request line was journaled; a malformed request line is kept whole here |
| `query` | string | requests | When the request line has a query string |
| `protocol` | string | all | When the start line was journaled |
| `status` | number | responses | `client_response`: with an end event, at every level. `upstream_response`: when the status line was journaled |
| `reason` | string | responses | When the status line has a reason phrase |
| `targets` | array of strings | `upstream_request` | With an end event: the upstream URLs tried, in order. When the last one answered, its response is the one recorded; when none did, the recorded response is the gateway's own `502`, `503` or `504` |
| `headers` | object of string arrays | all | At `HEADERS` and `FULL`, when there are any |
| `header_bytes` | number | client legs | With an end event: bytes of the head on the wire |
| `body_bytes` | number | client legs | With an end event: bytes of the body |
| `body` | string | client legs | With `bodies: true`, at `FULL`, when there is a body and no WARC record stores it: base64 |
| `checksum` | string | client legs | At `FULL`, when there is a body: the CRC32C the gateway recorded, as `crc32c:` and eight hex digits |
| `observed_checksum` | string | client legs | Only when the body read back does not match `checksum` |
| `start` | timestamp | `upstream_response` | With an end event: when the request to the upstream started |
| `first_byte` | timestamp | `upstream_response` | With an end event, when the upstream answered: when its response head had been read |
| `end` | timestamp | `upstream_response` | With an end event, when the upstream's response was read to its end; not for a failed relay or a WebSocket tunnel |
| `duration` | number | `upstream_response` | With `end`: `end` minus `start`, in seconds |

A new field may be added in a later version; a consumer should ignore fields it does not know.
Removing or changing a field is a breaking change.

### Other layouts

Each of these is a `tailer.yaml` and replaces the defaults only where it says.

Every exchange in the WARC archive, with a CDXJ index, so every complete exchange's JSON line has a `warc` pointer:

```yaml title="tailer.yaml"
warc:
  exchanges: all
  cdxj_index: true
```

JSON to rotating files for a loader instead of standard output:

```yaml title="tailer.yaml"
json:
  output: file
  output_dir: /json
  max_file_age: 5m
```

JSON lines alone, bodies included:

```yaml title="tailer.yaml"
warc:
  enabled: false
json:
  bodies: true
```

## 4. Retention: The Reaper

**Image:** `ghcr.io/ethlo/r7-reaper:latest`

The tailer is read-only by design (see §2's warning) — something else has to delete a
sealed segment once it is no longer needed, or `journal_dir` grows without bound. That something
is the reaper: a small standalone process whose only job is deleting old segments from a shared
journal volume.

!!! warning "`ttl` deletes whether or not a segment was read"
    With `tailers` listed, a segment is deleted early once every one of them is done with it
    (see the table below). Whatever the tailers say, a sealed (`.r7f`) segment is deleted once
    it has existed for longer than `ttl`, read or not: that is the bound on disk use. Set `ttl` comfortably
    longer than the slowest tailer's realistic lag — including time to recover from a restart —
    or a slow or temporarily-down tailer will lose data it never got a chance to read. A
    segment the gateway's recovery quarantined (`*.corrupt`, see
    `design/journal-invariants.md`) is reaped under the same `ttl`, since nothing will ever read
    it. A segment a *tailer* cannot read keeps its name: the tailer records that in its own
    checkpoint file and logs it, because the journal directory is read-only to it, and the
    file is reaped like any other sealed segment. Active (`.flux`) segments are never reaped, at any age — they belong exclusively to the
    gateway process that may still be writing to them.

    Age is read from the timestamp embedded in the segment's own filename, never from the
    file's filesystem last-modified time: mtime is reset by a backup restore, an `rsync` run
    without `-a`, or a volume migration, any of which would otherwise silently defeat `ttl`. A
    segment whose name the reaper cannot parse is left alone rather than guessed at from
    mtime — it is simply never a deletion candidate.

**Configuration:** same YAML config mechanism as the tailers — `reaper.yaml` by default,
overridable via the `REAPER_CONFIG` env var.

| Field           | Default     | Meaning                                                                 |
|------------------|-------------|--------------------------------------------------------------------------|
| `journal_dir`     | `/journals` | Directory the reaper scans (and deletes from) — mount this read-write, unlike every tailer |
| `ttl`             | `7d`        | How long a sealed segment is kept, counted from the last-event timestamp embedded in its filename (not the file's filesystem mtime — see below), before it is deleted. Supports `ms`, `s`, `m`, `h`, `d` |
| `tailers`         | none        | Checkpoint directories of the tailers that must all be done with a segment before it is deleted ahead of `ttl` (mount them read-only). A segment is done for a tailer when its checkpoint file says it was read to the end and everything in it delivered, and the tailer does not need it to rebuild an exchange still open. A listed tailer with no readable checkpoint file stops early deletion, so a tailer that is down keeps everything for up to `ttl` |
| `min_age`         | `1h`        | How old a segment must be before it is deleted ahead of `ttl`. A tailer counts a segment as done once its output reached the OS, not the disk; keep this above the tailers' `max_file_age` (15 minutes by default), after which their output files are fsync'd, so a power loss cannot take both copies |
| `poll_interval`   | `1m`        | Delay between sweeps. Supports `ms`, `s`, `m`, `h`, `d`                  |

**Example `config/reaper.yaml`:**

```yaml title="reaper.yaml"
journal_dir: /journals
ttl: 7d
# Delete as soon as the tailer is done with a segment (and it is an hour old), not after 7 days
tailers:
  - /checkpoints/tailer
```

**Who may write what.** Each component writes only what it owns and reads the rest, and the
mounts enforce it:

| | `r7-journals` | a tailer's checkpoints | a tailer's output |
| --- | --- | --- | --- |
| gateway | writes (segments, `.seq`, `.ctl`) | — | — |
| tailer | reads | writes its own | writes its own |
| reaper | deletes sealed and quarantined segments | reads | — |

So the reaper can delete what the gateway wrote, a tailer cannot, and the reaper cannot touch
anything a tailer wrote. The one thing that crosses a boundary, the reaper trusting a tailer's
checkpoint file, can only keep a segment longer: anything short of "done" leaves it for `ttl`.

**Example Docker Compose Integration:**

```yaml title="docker-compose.yaml"
services:
  r7-api:
    image: ghcr.io/ethlo/r7-gateway:latest
    volumes:
      - r7-journals:/journals:rw

  r7-tailer:
    image: ghcr.io/ethlo/r7-tailer:latest
    volumes:
      - ./config/tailer.yaml:/app/config/tailer.yaml:ro
      - r7-journals:/journals:ro                # reads only; retention is the reaper's job
      - r7-warc:/warc:rw
      - r7-tailer-checkpoints:/checkpoints:rw   # its own progress

  r7-reaper:
    image: ghcr.io/ethlo/r7-reaper:latest
    volumes:
      - ./config/reaper.yaml:/app/config/reaper.yaml:ro
      - r7-journals:/journals:rw                         # the only container that deletes from it
      - r7-tailer-checkpoints:/checkpoints/tailer:ro    # read to learn what the tailer is done with; never its output

volumes:
  r7-journals:
  r7-warc:
  r7-tailer-checkpoints:

```

## 5. Visualizing your data

Once your data is routed through the tailer:

* **JSON lines with Promtail/Loki:** use Grafana's LogQL to filter and aggregate your gateway traffic, extracting metrics from the JSON fields (like `duration`, `client_response.status` — `client_response_status` after LogQL's `json` parser — or specific headers).
* **WARC files:** these are for archival and replay, not dashboards. Feed them to a WARC-aware tool (e.g. [pywb](https://github.com/webrecorder/pywb)) to replay captured traffic, or look an exchange up by URL and time in the CDXJ index.
* **Into ClickHouse:** r7 ships no ClickHouse output. Set `json.output: file` and batch-insert the sealed `.jsonl` files with a loader of your own (one JSON object per line, which ClickHouse reads as `JSONEachRow`), then query them from Grafana with the ClickHouse plugin. The `warc` pointer in each row leads from a query result to the archived exchange.
