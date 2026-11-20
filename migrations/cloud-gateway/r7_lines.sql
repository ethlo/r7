-- r7's JSON lines into cloud-gateway's `log` table, in the shape cloud-gateway writes its rows.
-- Run once in the database that holds `log`. Written against ClickHouse 26.8.
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

CREATE MATERIALIZED VIEW IF NOT EXISTS r7_lines_to_log TO log AS
WITH
    JSONExtract(line, 'Tuple(
        request_id String,
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
    -- cloud-gateway leaves out parameters that are not accept-listed.
    arrayFilter(p -> NOT startsWith(p.1, 'fp:') AND NOT startsWith(p.2, 'fp:'),
        arrayMap(s -> (decodeURLFormComponent(splitByChar('=', s, 2)[1]),
                       decodeURLFormComponent(if(position(s, '=') > 0, splitByChar('=', s, 2)[2], ''))),
                 if(rq.query = '', [], splitByChar('&', rq.query)))) AS qp,
    arrayDistinct(arrayMap(p -> p.1, qp)) AS qnames,
    '(?i)^(text/|application/([a-z0-9.+-]*\\+)?(json|xml|x-www-form-urlencoded|javascript|graphql))' AS text_ct
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
    toInt32OrNull(toString(rq.body_bytes))                                     AS request_body_size,
    toInt32OrNull(toString(rs.body_bytes))                                     AS response_body_size,
    toInt32OrNull(toString(ifNull(rq.header_bytes, 0) + ifNull(rq.body_bytes, 0))) AS request_total_size,
    toInt32OrNull(toString(ifNull(rs.header_bytes, 0) + ifNull(rs.body_bytes, 0))) AS response_total_size,
    -- Inline bodies (json.bodies: inline) only for text; binary stays out.
    if(rq.body IS NOT NULL AND match(ifNull(rq_ct, ''), text_ct), tryBase64Decode(assumeNotNull(rq.body)), NULL) AS request_body,
    if(rs.body IS NOT NULL AND match(ifNull(rs_ct, ''), text_ct), tryBase64Decode(assumeNotNull(rs.body)), NULL) AS response_body,
    rq.body_ref.file                                                           AS request_body_file,
    rq.body_ref.offset                                                         AS request_body_offset,
    rq.body_ref.length                                                         AS request_body_length,
    rs.body_ref.file                                                           AS response_body_file,
    rs.body_ref.offset                                                         AS response_body_offset,
    rs.body_ref.length                                                         AS response_body_length
FROM r7_lines
-- A journal rule picked the exchange, and it completed (an incomplete one has no start or status).
WHERE has(mapKeys(e.attributes), 'gateway.journal.rule')
  AND e.start IS NOT NULL
  AND rs.status IS NOT NULL;

-- Lines that are not JSON, kept short for a look, then dropped after 30 days.
CREATE TABLE IF NOT EXISTS r7_rejected
(
    received DateTime DEFAULT now(),
    line     String
)
ENGINE = MergeTree
ORDER BY received
TTL received + INTERVAL 30 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS r7_lines_rejected TO r7_rejected AS
SELECT now() AS received, left(line, 2048) AS line
FROM r7_lines
WHERE NOT isValidJSON(line);
