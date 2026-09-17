-- Header-heavy GET: browser baseline plus proxy/tracing headers (36 total).
--
-- This is what a request looks like after it has passed a CDN, a WAF and a
-- service mesh. It targets the header-storage path (ArrayBackedPairStorage)
-- and the journal HEADERS encoder specifically.
--
-- Self-contained by design: wrk loads the script once per thread, and relative
-- `dofile` resolution inside wrk is not something to bet a benchmark on.

wrk.method = "GET"

local h = {
  -- browser baseline (keep in sync with browser-get.lua)
  ["User-Agent"] = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
  ["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
  ["Accept-Encoding"] = "gzip, deflate, br, zstd",
  ["Accept-Language"] = "en-GB,en-US;q=0.9,en;q=0.8,nb;q=0.7",
  ["Cache-Control"] = "no-cache",
  ["Pragma"] = "no-cache",
  ["Sec-Ch-Ua"] = '"Chromium";v="141", "Not?A_Brand";v="24", "Google Chrome";v="141"',
  ["Sec-Ch-Ua-Mobile"] = "?0",
  ["Sec-Ch-Ua-Platform"] = '"Linux"',
  ["Sec-Fetch-Dest"] = "document",
  ["Sec-Fetch-Mode"] = "navigate",
  ["Sec-Fetch-Site"] = "none",
  ["Sec-Fetch-User"] = "?1",
  ["Upgrade-Insecure-Requests"] = "1",
  ["Cookie"] = "session=f47ac10b58cc4372a5670e02b2c3d479; theme=dark; _ga=GA1.2.1234567890.1700000000",
  ["Referer"] = "https://app.ethlo.com/dashboard",

  -- infrastructure hops
  ["X-Forwarded-For"] = "203.0.113.42, 198.51.100.7, 192.0.2.1",
  ["X-Forwarded-Proto"] = "https",
  ["X-Forwarded-Host"] = "app.ethlo.com",
  ["X-Forwarded-Port"] = "443",
  ["X-Real-IP"] = "203.0.113.42",
  ["X-Request-ID"] = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  ["X-Correlation-ID"] = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  ["X-Amzn-Trace-Id"] = "Root=1-67891233-abcdef012345678912345678",
  ["Traceparent"] = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
  ["Tracestate"] = "ethlo=t61rcWkgMzE,congo=t61rcWkgMzE",
  ["Baggage"] = "userId=alice,serverNode=DF28,isProduction=false",
  ["X-Cloud-Trace-Context"] = "105445aa7843bc8bf206b12000100000/1;o=1",
  ["Cf-Ray"] = "8b2f1c4d5e6a7b8c-OSL",
  ["Cf-Ipcountry"] = "NO",
  ["Cf-Visitor"] = '{"scheme":"https"}',
  ["X-Envoy-External-Address"] = "203.0.113.42",
  ["X-Envoy-Expected-Rq-Timeout-Ms"] = "15000",
  ["X-B3-Traceid"] = "80f198ee56343ba864fe8b2a57d3eff7",
  ["X-B3-Spanid"] = "e457b5a2e4d86bd1",
  ["X-B3-Sampled"] = "1",
  ["Via"] = "1.1 cloudfront, 1.1 envoy",
}

for k, v in pairs(h) do
  wrk.headers[k] = v
end
