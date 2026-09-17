-- POST with a ~1KB JSON body.
--
-- Exercises the request-body path: teeing conduits, journal BODY markers and
-- the upstream write. GET benchmarks never touch this code, which is why a
-- gateway can look fast and still fall over on real API traffic.
--
-- Body size is overridable: BENCH_BODY_BYTES=4096 wrk -s post-json.lua ...

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
wrk.headers["Content-Type"]  = "application/json"
wrk.headers["Accept"]        = "application/json"
wrk.headers["User-Agent"]    = "r7-benchmark/1.0"
wrk.headers["Authorization"] = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.benchmark.signature"
wrk.headers["X-Request-ID"]  = "f47ac10b-58cc-4372-a567-0e02b2c3d479"

function done(summary, latency, requests)
  io.write(string.format("\n[post-json] body bytes sent per request: %d\n", #body))
end
