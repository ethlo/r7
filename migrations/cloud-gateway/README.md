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

1. Write JSON lines to files, with bodies in body files if you keep bodies, in `tailer.yaml`:

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
| `query_params` | The parameters on r7's `safe_query_parameters`; fingerprinted ones are left out, as cloud-gateway leaves out parameters not on its accept list |
| `*_body_size`, `*_total_size` | `body_bytes`, and `header_bytes` plus `body_bytes`, of the client legs |
| `request_body`, `response_body` | A body in the line (`json.bodies: inline`) with a text content type; binary bodies stay out |
| `*_body_file`, `*_body_offset`, `*_body_length` | `body_ref` of the client legs: read `length` bytes at `offset` and `zstd -d` them |

Only exchanges a journal rule picked are loaded, as cloud-gateway logs only matched ones. Lines
of exchanges the tailer gave up on (`incomplete`, no `start` or status) are skipped.

## What differs from cloud-gateway's rows

- Header and query values outside r7's safe lists are `fp:` fingerprints, where cloud-gateway
  logs headers as sent unless told otherwise. See
  [redaction](https://r7.ethlo.com/journaling/#redacted-header-and-query-parameter-values).
- `Content-Type` is the value as sent; cloud-gateway normalises it.
- `request_raw`, `response_raw`, `exception_type` and `exception_message` stay empty: r7 has no
  equivalent. The exchange's record is the JSON line, and the WARC records if you archive.
