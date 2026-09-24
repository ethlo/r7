# Journal Tailing and Observability

ethlo r7 is designed for absolute minimum latency. Instead of serializing logs to text or establishing network connections to logging databases on the request thread, r7 writes raw traffic data directly to memory-mapped binary files (journals) on disk.

To ingest these logs into your observability stack (like Grafana, ELK, or ClickHouse), r7 uses **Tailers**. Tailers run as separate processes or sidecar containers, reading the binary journals asynchronously without impacting the gateway's performance.

## The Sidecar Deployment Pattern

In containerized environments, the gateway and the tailer share a volume. The gateway writes the binary journals, and the tailer reads them.

!!! warning "The journal mount must be read-write on the tailer side too"
    A tailer does more than read: it deletes segments once they are fully processed and persists
    a `.r7_checkpoints` file under the same directory so it can resume where it left off after a
    restart. Mounting `/journals` read-only on the tailer will not fail loudly — it fails as
    segments silently piling up and the checkpoint file never being written.

!!! warning "Only one tailer may consume a given journal directory"
    `R7Tailer` always deletes segments once fully processed and always persists its own
    `.r7_checkpoints` file — there is no "read-only" or "best-effort" mode today. Two tailer
    containers pointed at the same `/journals` (say, one JSON and one WARC) race on both, and
    one of them will silently lose records to the other's deletions and checkpoint writes. Run
    exactly one tailer per journal directory; if you need both JSON and WARC output from the
    same traffic, that is not yet supported by a single gateway journal.

```mermaid
graph LR
    Client -->|HTTP| R7[r7 gateway Container]
    R7 -->|Binary Write| Vol[(Shared Volume: /journals)]
    Vol -->|Binary Read| Tailer[r7 Tailer Container]
    Tailer -->|JSON / Batch Insert| Ops[Observability Stack]

```

---

## Tailer Implementations

We provide pre-built Docker images for the most common observability architectures.

### 1. Standard JSON Tailer (Universal)

**Image:** `ghcr.io/ethlo/r7-tailer-json:latest`

This tailer converts the binary journal entries into verbose JSON and streams them to standard output (`stdout`) by default. This is the recommended approach if you use generic log forwarders like **Promtail (for Grafana Loki)**, **Fluent Bit**, or **Vector**.

**Configuration (environment variables):**

| Variable          | Default     | Meaning                                                                 |
|-------------------|-------------|--------------------------------------------------------------------------|
| `JOURNAL_DIR`     | `/journals` | Directory the tailer reads binary journals from                          |
| `OUTPUT_PATH`     | `-`         | Where JSON lines are written; `-` (or `stdout`) means standard output, any other value is a file path (appended to, parent directories created if missing) |
| `MIN_AGE_SECONDS` | `3600`      | How old a segment must be before it is eligible for tailing/deletion     |
| `POLL_INTERVAL_MS`| `1000`      | Delay between tailer ticks                                                |
| `PRETTY_PRINT`    | `false`     | Pretty-print the JSON output                                              |

**Example Docker Compose Integration:**

```yaml
services:
  r7-api:
    image: ghcr.io/ethlo/r7-gateway:latest
    volumes:
      - ./config:/app/config:ro
      - r7-journals:/journals:rw # Mount the shared volume

  r7-tailer-json:
    image: ghcr.io/ethlo/r7-tailer-json:latest
    volumes:
      - r7-journals:/journals:rw # r7Tailer deletes completed segments and writes its checkpoint file here
    environment:
      - JOURNAL_DIR=/journals
    # The output of this container goes to Docker's stdout, 
    # ready to be scraped by your infrastructure's logging driver.

volumes:
  r7-journals:

```

### 2. WARC/zstd Tailer (Archival)

**Image:** `ghcr.io/ethlo/r7-tailer-warc:latest`

This tailer writes completed exchanges as [WARC 1.1](https://iipc.github.io/warc-specifications/specifications/warc-format/warc-1.1/) records — the standard web-archiving format used by the Internet Archive and national libraries — to rotating `.warc.zst` files. Use this when you need a durable, replayable, tool-interoperable record of actual request/response traffic (compliance archiving, incident forensics, replaying real traffic against a new backend), rather than a queryable log stream.

Each WARC record is compressed as one independent Zstandard frame per the (proposed) IIPC "Zstandard Compression for WARC Files" convention — the same "record-at-a-time" approach `.warc.gz` has used for gzip since WARC/1.0. `zstd -d` (or any zstd-aware WARC tool) decompresses the whole file back to a plain WARC stream.

**Record shape.** Every exchange is written as up to four records, in the order they occurred: the client request, the forwarded upstream request, the upstream response, and the client response — linked to each other by repeated `WARC-Concurrent-To` fields (see [`design/warc.md`](https://github.com/ethlo/r7/blob/main/design/warc.md) for the full rationale). The gateway's journal stores exactly one copy of a request body and one copy of a response body per exchange — not one per network leg — so the upstream request/response records never have a payload of their own to write: each carries `WARC-Truncated: unspecified` plus the `WARC-Payload-Digest` of the payload the corresponding client-side record actually stores. This is deliberately not a `revisit` record — `revisit` means "unchanged since it was previously archived", which the second hop of one exchange is not. A request/response record with a body that was never captured at all — the journal level for that route/direction is below `FULL`, even though traffic was non-zero — is likewise marked `WARC-Truncated: unspecified`, so it cannot be mistaken for a message that genuinely had no body. Every record also carries a per-file, monotonically increasing `WARC-X-R7-Sequence`, so a reader can tell a shorter-than-expected file apart from one that finished cleanly. All records of one exchange are written to disk as a single batch, but the underlying write is not guaranteed atomic (a full disk can fail partway through). If it fails, the writer never seals the file it was writing to — it deletes the whole `.open` file, including any earlier, successfully written groups, and opens a fresh one for the retried exchange, so a sealed `.warc.zst` file is never left with a truncated trailing frame. `R7Tailer` re-delivers the failed exchange on its next tick, and the tailer process itself survives the failure to retry it.

**Cross-exchange deduplication.** Separately, two genuinely different exchanges (a repeated static asset, a cached response) can produce byte-identical payloads. The client request/response records participate in this via a bounded, digest-keyed cache of payloads already written in full: the first record needing a given payload (by SHA-256) is written normally, and a later record for a *different* exchange needing the same payload is written as a `revisit` record per the WARC 1.1 `identical-payload-digest` profile — headers preserved, payload omitted, `WARC-Truncated: length`, and a `WARC-Refers-To`/`-Target-URI`/`-Date` pointing back at the original record. The cache is only ever consulted or updated after a whole exchange's own records have been decided, so a request and response that happen to share a payload (e.g. both empty) within the *same* exchange are never revisited against each other.

**Checksum integrity.** If the journal itself reports a body checksum mismatch for a leg (stored bytes don't match what the gateway recorded at request time), that leg's records are written without a payload or digest, marked `WARC-Truncated: unspecified` and `WARC-R7-Checksum-Mismatch: true`, instead of silently archiving corrupted bytes as an authoritative record.

**Configuration (environment variables):**

| Variable                   | Default     | Meaning                                                                    |
|----------------------------|-------------|-----------------------------------------------------------------------------|
| `JOURNAL_DIR`               | `/journals` | Directory the tailer reads binary journals from                             |
| `OUTPUT_DIR`                | `/warc`     | Directory rotated `.warc.zst` files are written to                          |
| `WARC_FILE_PREFIX`          | `r7`        | Filename prefix for rotated WARC files                                      |
| `WARC_MAX_FILE_SIZE_BYTES`  | `1000000000`| Rotate to a new file once the current one reaches this size (at least 65536; a size that couldn't hold a single record is refused at startup) |
| `WARC_MAX_FILE_AGE_SECONDS` | `900`       | Rotate to a new file once the current one is this old, even under light/no traffic (size-or-age rollover) |
| `ZSTD_LEVEL`                | `9`         | Zstandard compression level (1-22), applied per WARC record                 |
| `DEDUP_CACHE_ENTRIES`       | `100000`    | Max number of payload digests remembered for cross-exchange revisit dedup   |
| `MIN_AGE_SECONDS`           | `3600`      | How old a segment must be before it is eligible for tailing/deletion        |
| `POLL_INTERVAL_MS`          | `1000`      | Delay between tailer ticks                                                   |

**Example Docker Compose Integration:**

```yaml
services:
  r7-api:
    image: ghcr.io/ethlo/r7-gateway:latest
    volumes:
      - r7-journals:/journals:rw

  r7-tailer-warc:
    image: ghcr.io/ethlo/r7-tailer-warc:latest
    volumes:
      - r7-journals:/journals:rw
      - r7-warc:/warc:rw
    environment:
      - JOURNAL_DIR=/journals
      - OUTPUT_DIR=/warc

volumes:
  r7-journals:
  r7-warc:

```

### 3. ClickHouse Tailer

!!! important
    Not yet ready!

**Image:** `ghcr.io/ethlo/r7-tailer-clickhouse:latest`

For extremely high-throughput environments, storing access logs in a standard inverted-index database (like Elasticsearch) becomes prohibitively expensive. ClickHouse is a columnar database uniquely suited for this scale.

This tailer reads the binary journals and performs optimized, asynchronous batch inserts directly into ClickHouse using the `JSONEachRow` format.

**Example Docker Compose Integration:**

```yaml
services:
  r7-api:
    image: ghcr.io/ethlo/r7-gateway:latest
    volumes:
      - r7-journals:/journals:rw

  r7-tailer-clickhouse:
    image: ghcr.io/ethlo/r7-tailer-clickhouse:latest
    volumes:
      - r7-journals:/journals:rw
    environment:
      - JOURNAL_DIR=/journals
      - CLICKHOUSE_URL=jdbc:clickhouse://clickhouse-server:8123/r7_logs
      - CLICKHOUSE_USER=default
      - CLICKHOUSE_PASSWORD=secret
      - BATCH_SIZE=10000
      - FLUSH_INTERVAL_MS=1000

volumes:
  r7-journals:

```

## Visualizing in Grafana

Once your data is routed through a tailer:

* **If using the JSON Tailer with Promtail/Loki:** You can use Grafana's LogQL to filter and aggregate your gateway traffic, extracting metrics dynamically from the JSON fields (like `duration`, `status`, or specific headers).
* **If using the WARC Tailer:** WARC files are for archival/replay, not dashboards — feed them to a WARC-aware tool (e.g. [pywb](https://github.com/webrecorder/pywb)) to replay captured traffic, or to an indexer for forensic search.
* **If using the ClickHouse Tailer:** Install the official ClickHouse plugin for Grafana. You can write standard SQL queries against the `r7_logs` table to build blazing-fast dashboards for latency percentiles, error rates, and traffic volume.