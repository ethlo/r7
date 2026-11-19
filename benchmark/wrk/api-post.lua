-- API POST: create a resource with a ~1 KB JSON body.
--
-- The same headers as api-get.lua plus Content-Type; wrk adds Content-Length.
-- Exercises the request-body path: teeing conduits, journal BODY markers and
-- the upstream write. The backend answers 201 with a small JSON body.
--
-- Body size is overridable: BENCH_BODY_BYTES=4096 wrk -s api-post.lua ...

local target = tonumber(os.getenv("BENCH_BODY_BYTES")) or 1024

local function build_body(n)
  local head = '{"event":"order.created","id":"f47ac10b-58cc-4372-a567-0e02b2c3d479",' ..
               '"ts":"2026-01-01T12:00:00Z","amount":1299,"currency":"NOK","items":['
  local tail = ']}'
  local parts = {}
  local size = #head + #tail
  local i = 0
  while size < n do
    i = i + 1
    local item = string.format('{"sku":"SKU-%06d","qty":%d,"price":%d}', i, (i % 5) + 1, 100 + i)
    if i > 1 then item = "," .. item end
    parts[#parts + 1] = item
    size = size + #item
  end
  return head .. table.concat(parts) .. tail
end

local body = build_body(target)

wrk.method = "POST"
wrk.body   = body

-- An opaque bearer token the size of a typical signed JWT (432 characters). Not a JWT in
-- form, so the repository's secret scan has nothing to flag.
local token = string.rep("Kx9fQ2mZ", 54)

local h = {
  ["Content-Type"] = "application/json",
  ["Accept"] = "application/json",
  ["Accept-Encoding"] = "gzip",
  ["User-Agent"] = "orders-client/3.4.1 (okhttp/4.12.0)",
  ["Authorization"] = "Bearer " .. token,
  ["X-Request-ID"] = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  ["Traceparent"] = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
  ["X-Forwarded-For"] = "203.0.113.42",
  ["X-Forwarded-Proto"] = "https",
  ["X-Forwarded-Host"] = "api.example.com",
}

for k, v in pairs(h) do
  wrk.headers[k] = v
end

function done(summary, latency, requests)
  io.write(string.format("\n[api-post] body bytes sent per request: %d\n", #body))
end
