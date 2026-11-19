-- API GET: one JSON resource, as a service or mobile client asks for it.
--
-- Ten headers with Host: a bearer token, a trace context, a request id and what
-- the load balancer in front adds. The backend answers with a 2 KB JSON body.
--
-- Self-contained by design, like the other scripts: wrk loads each script once
-- per thread. Keep the headers in sync with api-post.lua.

wrk.method = "GET"

-- An opaque bearer token the size of a typical signed JWT (432 characters). Not a JWT in
-- form, so the repository's secret scan has nothing to flag.
local token = string.rep("Kx9fQ2mZ", 54)

local h = {
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
