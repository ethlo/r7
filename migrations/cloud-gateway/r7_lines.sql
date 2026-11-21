-- r7's JSON lines into cloud-gateway's `log` table, in the shape cloud-gateway writes its rows.
-- Run in the database that holds `log`. Written against ClickHouse 26.8.
-- Safe to run again to pick up a newer version: tables are kept, the views are recreated (rows
-- stay in `log`). Stop Vector first, as lines sent while a view is missing are not loaded.
--
-- Vector posts the lines as they are (FORMAT LineAsString) into r7_lines, a Null table that
-- stores nothing; the materialized view parses each line once and writes the row to `log`.

-- Where a body file holds each body (tailer json.bodies: files). Nullable, so existing rows and
-- exchanges without a stored body leave them empty.
ALTER TABLE log
    ADD COLUMN IF NOT EXISTS request_body_file    Nullable(String),
    ADD COLUMN IF NOT EXISTS request_body_offset  Nullable(Int64),
    ADD COLUMN IF NOT EXISTS request_body_length  Nullable(Int64),
    ADD COLUMN IF NOT EXISTS response_body_file   Nullable(String),
    ADD COLUMN IF NOT EXISTS response_body_offset Nullable(Int64),
    ADD COLUMN IF NOT EXISTS response_body_length Nullable(Int64);

CREATE TABLE IF NOT EXISTS r7_lines
(
    line String
)
ENGINE = Null;

DROP VIEW IF EXISTS r7_lines_to_log;
CREATE MATERIALIZED VIEW r7_lines_to_log TO log AS
WITH
    JSONExtract(line, 'Tuple(
        request_id String,
        incomplete Nullable(String),
        route_id String,
        start Nullable(String),
        duration Nullable(Float64),
        identity Tuple(issuer Nullable(String), subject Nullable(String), username Nullable(String)),
        client_request Tuple(method String, path String, query String,
                             headers Map(String, Array(String)),
                             header_bytes Nullable(Int64), body_bytes Nullable(Int64), body Nullable(String),
                             body_ref Tuple(file Nullable(String), offset Nullable(Int64), length Nullable(Int64))),
        upstream_request Tuple(targets Array(String)),
        client_response Tuple(status Nullable(Int32),
                              headers Map(String, Array(String)),
                              header_bytes Nullable(Int64), body_bytes Nullable(Int64), body Nullable(String),
                              body_ref Tuple(file Nullable(String), offset Nullable(Int64), length Nullable(Int64))),
        attributes Map(String, Array(String))
    )') AS e,
    e.client_request AS rq,
    e.client_response AS rs,
    e.identity AS id,
    -- Header names as sent, so match the dedicated columns case-insensitively.
    mapFilter((k, v) -> lower(k) = 'host', rq.headers) AS h_host,
    mapFilter((k, v) -> lower(k) = 'user-agent', rq.headers) AS h_ua,
    mapFilter((k, v) -> lower(k) = 'content-type', rq.headers) AS h_rq_ct,
    mapFilter((k, v) -> lower(k) = 'content-type', rs.headers) AS h_rs_ct,
    nullIf(arrayElement(mapValues(h_rq_ct), 1)[1], '') AS rq_ct,
    nullIf(arrayElement(mapValues(h_rs_ct), 1)[1], '') AS rs_ct,
    -- Query parameters r7 fingerprinted (outside safe_query_parameters) are left out, as
    -- cloud-gateway leaves out parameters that are not accept-listed. Names and values stay
    -- as sent, not percent-decoded, as cloud-gateway stores them.
    arrayFilter(p -> NOT startsWith(p.1, 'fp:') AND NOT startsWith(p.2, 'fp:'),
        arrayMap(s -> (if(position(s, '=') > 0, substring(s, 1, position(s, '=') - 1), s),
                       if(position(s, '=') > 0, substring(s, position(s, '=') + 1), '')),
                 arrayFilter(s -> s != '', splitByChar('&', rq.query)))) AS qp,
    arrayDistinct(arrayMap(p -> p.1, qp)) AS qnames,
    ifNull(rq.header_bytes, 0) + ifNull(rq.body_bytes, 0) AS rq_total,
    ifNull(rs.header_bytes, 0) + ifNull(rs.body_bytes, 0) AS rs_total
SELECT
    parseDateTime64BestEffort(assumeNotNull(e.start), 3, 'UTC')                AS timestamp,
    e.route_id                                                                 AS route_id,
    e.attributes['gateway.journal.rule'][1]                                    AS matcher_id,
    arrayElement(e.upstream_request.targets, -1)                               AS route_uri,
    e.request_id                                                               AS gateway_request_id,
    rq.method                                                                  AS method,
    rq.path                                                                    AS path,
    CAST(NULL AS Nullable(String))                                             AS query,
    mapFromArrays(qnames, arrayMap(n -> arrayMap(p -> p.2, arrayFilter(p -> p.1 = n, qp)), qnames))
                                                                               AS query_params,
    toInt64(floor(assumeNotNull(e.duration) * 1000 + 0.000001))                AS response_time,
    assumeNotNull(rs.status)                                                   AS status,
    toUInt8(assumeNotNull(rs.status) >= 400)                                   AS is_error,
    coalesce(id.username, id.subject)                                          AS user_claim,
    if(id.issuer IS NULL, NULL,
       coalesce(nullIf(extract(assumeNotNull(id.issuer), '/realms/([^/]+)/?$'), ''), id.issuer))
                                                                               AS realm_claim,
    nullIf(arrayElement(mapValues(h_host), 1)[1], '')                          AS host,
    nullIf(arrayElement(mapValues(h_ua), 1)[1], '')                            AS user_agent,
    rq_ct                                                                      AS request_content_type,
    rs_ct                                                                      AS response_content_type,
    mapApply((k, v) -> (k, arrayStringConcat(v, ', ')),
             mapFilter((k, v) -> lower(k) NOT IN ('host', 'user-agent', 'content-type'), rq.headers))
                                                                               AS request_headers,
    mapApply((k, v) -> (k, arrayStringConcat(v, ', ')), rs.headers)            AS response_headers,
    -- The table's size columns are Int32: a larger size is left empty rather than wrapped.
    if(rq.body_bytes <= 2147483647, toInt32(rq.body_bytes), NULL)                AS request_body_size,
    if(rs.body_bytes <= 2147483647, toInt32(rs.body_bytes), NULL)                AS response_body_size,
    if(rq_total <= 2147483647, toInt32(rq_total), NULL)                          AS request_total_size,
    if(rs_total <= 2147483647, toInt32(rs_total), NULL)                          AS response_total_size,
    -- Inline bodies (json.bodies: inline), as journaled: binary bodies as bytes, a body sent with a
    -- Content-Encoding (gzip, br, ...) still compressed.
    tryBase64Decode(rq.body)                                                   AS request_body,
    tryBase64Decode(rs.body)                                                   AS response_body,
    rq.body_ref.file                                                           AS request_body_file,
    rq.body_ref.offset                                                         AS request_body_offset,
    rq.body_ref.length                                                         AS request_body_length,
    rs.body_ref.file                                                           AS response_body_file,
    rs.body_ref.offset                                                         AS response_body_offset,
    rs.body_ref.length                                                         AS response_body_length
FROM r7_lines
-- A journal rule picked the exchange, and it completed.
WHERE has(mapKeys(e.attributes), 'gateway.journal.rule')
  AND e.incomplete IS NULL
  AND e.start IS NOT NULL
  AND rs.status IS NOT NULL;

-- Lines that reach ClickHouse but are not JSON, kept short for a look, then dropped after 30
-- days. Vector forwards only lines that mention a journal rule, so this catches a broken one of
-- those; the tailer itself writes whole lines only.
CREATE TABLE IF NOT EXISTS r7_rejected
(
    received DateTime DEFAULT now(),
    line     String
)
ENGINE = MergeTree
ORDER BY received
TTL received + INTERVAL 30 DAY;

DROP VIEW IF EXISTS r7_lines_rejected;
CREATE MATERIALIZED VIEW r7_lines_rejected TO r7_rejected AS
SELECT now() AS received, left(line, 2048) AS line
FROM r7_lines
WHERE NOT isValidJSON(line);
