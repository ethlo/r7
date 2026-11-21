# Moving from cloud-gateway to r7

[cloud-gateway](https://github.com/ethlo/cloud-gateway) logs each exchange straight into a
ClickHouse table, `log`. These two files load r7's JSON lines into that same table, in the shape
cloud-gateway writes its rows, so existing queries and dashboards keep working while traffic
moves to r7. For a new setup, use the table in
[Where r7 fits](https://r7.ethlo.com/where-r7-fits/) instead: it keeps every header value and
every leg of the exchange.

| File | What it does |
| --- | --- |
| [`r7_lines.sql`](r7_lines.sql) | Adds the body-file columns to `log`, a Null table `r7_lines` for the incoming lines, a materialized view that writes each line into `log`, and `r7_rejected` for lines that are not JSON (kept 30 days) |
| [`vector.yaml`](vector.yaml) | Reads the tailer's sealed `.jsonl` files and posts the lines unparsed to `r7_lines` |

Vector only moves bytes here; ClickHouse parses each line once, in the view. Parsing and
reshaping the lines in Vector's VRL instead costs several times the CPU.

## Setup

1. Write JSON lines to files, in `tailer.yaml`. Where the bodies go decides whether
   `request_body` and `response_body` are filled.

   **Bodies in ClickHouse**, as cloud-gateway stores them: each body travels in its line,
   base64-encoded, and the view decodes it into the column. Every body is loaded as r7 journaled it:
   binary bodies as bytes, and a body sent with a `Content-Encoding` still compressed.

   ```yaml
   warc:
     enabled: false
   json:
     output: file
     output_dir: /json
     bodies: inline
   ```

   **Bodies beside ClickHouse**: each body goes to a body file, and the row holds only where
   to find it (`*_body_file`, `*_body_offset`, `*_body_length`).

   ```yaml
   warc:
     enabled: false
   json:
     output: file
     output_dir: /json
     bodies: files
     body_files:
       output_dir: /bodies
   ```

   Inline bodies make each line larger by about four thirds of its bodies, and everything on
   the way to ClickHouse scales with line size: the tailer's writes, Vector's reads and the
   view's parsing. ClickHouse stores the decoded bodies zstd-compressed. Bodies are only
   journaled at `FULL`; cap them per direction with
   [`truncate_body_at`](https://r7.ethlo.com/journaling/#capping-bodies), for example
   `response: { level: FULL, truncate_body_at: 64KB }` on a journal rule, and keep Vector's
   `max_line_bytes` above the largest line.

2. Run `r7_lines.sql` in the database that holds `log`.
3. Set your ClickHouse host, database and user in `vector.yaml`, put the password in
   `/run/secrets/clickhouse_password`, and start Vector. It needs a persistent `/var/lib/vector`.

## How a row is filled

| Column | From the JSON line |
| --- | --- |
| `timestamp` | `start`, to milliseconds |
| `route_id`, `gateway_request_id`, `method`, `path` | `route_id`, `request_id`, `client_request.method`, `client_request.path` |
| `route_uri` | The last of `upstream_request.targets`: the target that answered, or was tried last |
| `matcher_id` | The first `gateway.journal.rule` attribute: the id (or path) of the journal rule that picked the exchange |
| `response_time` | `duration` in milliseconds, truncated |
| `status`, `is_error` | `client_response.status`; `is_error` is 1 from 400 up |
| `user_claim` | `identity.username`, else `identity.subject` |
| `realm_claim` | The realm in a Keycloak issuer (`…/realms/<realm>`), else the whole `identity.issuer` |
| `host`, `user_agent`, `request_content_type` | The first value of that request header, which then stays out of `request_headers` |
| `response_content_type` | The first `Content-Type` value of the response |
| `request_headers`, `response_headers` | Each header as sent, its values joined with `, ` |
| `query` | Empty, as cloud-gateway leaves it |
| `query_params` | The parameters on r7's `safe_query_parameters`, as sent (not percent-decoded); fingerprinted ones are left out, as cloud-gateway leaves out parameters not on its accept list |
| `*_body_size`, `*_total_size` | `body_bytes`, and `header_bytes` plus `body_bytes`, of the client legs; empty above the column's Int32 range |
| `request_body`, `response_body` | The body in the line (`json.bodies: inline`), base64-decoded: binary bodies as bytes, an encoded body (gzip, br, ...) as sent. A body cut by `truncate_body_at` is stored as its kept prefix |
| `*_body_file`, `*_body_offset`, `*_body_length` | `body_ref` of the client legs: read `length` bytes at `offset` and `zstd -d` them |

Only exchanges a journal rule picked are loaded, as cloud-gateway logs only matched ones. Lines
of exchanges the tailer gave up on (`incomplete`) are skipped.

## What differs from cloud-gateway's rows

- Header and query values outside r7's safe lists are `fp:` fingerprints, where cloud-gateway
  logs headers as sent unless told otherwise. See
  [redaction](https://r7.ethlo.com/journaling/#redacted-header-and-query-parameter-values).
- A parameter with an empty value (`?token=`) is kept in `query_params` even when it is not on
  `safe_query_parameters`: r7 has nothing to fingerprint there, so it stays empty, and the view
  cannot tell it from a safe one. cloud-gateway drops it.
- `Content-Type` is the value as sent; cloud-gateway normalises it.
- A body sent with `Content-Encoding: gzip` or `deflate` is stored compressed, as r7 journaled
  it; cloud-gateway decompresses those two before storing. Brotli is stored compressed by both.
- `request_raw`, `response_raw`, `exception_type` and `exception_message` stay empty: r7 has no
  equivalent. The exchange's record is the JSON line, and the WARC records if you archive.
