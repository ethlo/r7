# The upstream client: `r7-upstream`

**Status:** current. This describes how r7 talks to upstreams today. How it came to be, with
the measurements behind each choice, is in [`history/upstream-client.md`](history/upstream-client.md).
Operator-facing settings are in `docs/config.md` ("Proxy Client").

## Shape

r7 proxies through one client of its own, `r7-upstream` (package `com.ethlo.r7.upstream`). It
depends on `r7-api` and `r7-server` and names no server type. Every server uses it:

```
r7-api ← r7-core ← r7-server ← r7-upstream ← r7-server-blocking ← r7-helidon, r7-servlet
```

The upstream exchange is **blocking, on a virtual thread**, with plain `java.net.Socket` I/O.
On Níma one virtual thread owns the whole request, client side and upstream side, so nothing
crosses threads. A non-blocking client was measured against this and lost on handoffs, not on
work (see the history).

It has two layers:

- **Codec (`Http1`)** does no I/O. It encodes the upstream request head, decides framing,
  parses and validates the upstream response head, and decodes chunked bodies. Request
  smuggling and response splitting are decided here, so this is the one place they are
  handled, and it is tested in isolation (`Http1Test`).
- **Transport (`HttpUpstream`, `UpstreamRelay`, `Tunnel`)** covers targets, the per-target
  connection pool, sockets and timeouts, and the loop that drives the codec between the
  upstream socket and the server's streams.

## The contract with a server

A server's exchange implements `ProxiedExchange`: the request as the pipeline leaves it
(sanitised and filtered), the client-side body streams, the client response, and per-block
callbacks for counting, limits and journal tees. `UpstreamRelay.relay` runs the whole exchange
on the calling thread. The relay does not know journaling exists. The server's exchange is the
`ProxiedExchange` itself, not an adapter around it, so there is one object per request.

`ProxyFailure` carries the status the client gets (502, 503, 504 or 413). The server answers
with it unless its response has already started, so error bodies are the same on every server.
When an upstream body fails part-way, the server aborts the client connection
(`abortResponse()`), so that a truncated body is never terminated as if it were complete.

## Rules the client keeps

- **Strict response framing.** Conflicting or repeated `Content-Length`, non-numeric or signed
  lengths and chunk sizes, whitespace before a header colon, folded headers, and transfer
  codings other than `chunked` are answered 502, and the connection is never pooled. Response
  heads are bounded at 64 KB and 200 lines, independently of the client-facing
  `max_header_size`.
- **`Connection` is tokenised.** `close` is found in any position, the headers it nominates are
  stripped, and an HTTP/1.0 response without `keep-alive` is not pooled.
- **Retries never repeat work.** A connect failure moves on to the next target, once per
  target. A failure on a reused connection is retried only when the request was not fully
  written or its method is idempotent (RFC 9110 §9.2.2).
- **An early upstream answer is kept.** If writing the request body fails, the relay tries to
  read a response before giving up, so an upstream's 413 is not turned into a 502.
- **Bounded waiting.** Per target, `max_connections_per_target` requests are in flight and
  `max_queue_size` wait; past that the answer is 503. `max_request_time` bounds the whole
  exchange, pool wait included, enforced by one sweeper thread that closes overdue
  connections. Idle pooled connections expire after `ttl`.
- **https upstreams** use `SSLSocket` from the JVM's default `SSLContext`, with SNI, and
  verify the certificate chain and the host name.
- **HTTP/2 to HTTP/1.1.** The client side can be h2c (`http.enable_http2`, off by default);
  the upstream hop is always HTTP/1.1. For requests that did not arrive over HTTP/1.x, the
  relay refuses with 400 what HTTP/2 can carry and HTTP/1.1 cannot: a body whose length
  differs from its `Content-Length`, a header name that is not a token, and a control
  character in a value or target.
- **WebSocket.** A 101 from the upstream, for a request that asked to upgrade, turns the
  connection into a `Tunnel` that copies bytes both ways until either side closes. It no longer
  counts against `max_request_time` or the pool. A 101 nobody asked for is refused with 502. On
  Níma, `WebSocketUpgrader` takes the handshake before routing.

The conformance kits hold every server to the same behaviour: `UpstreamConformanceKit`
(a scripted, misbehaving upstream, checking that the next request on the route is unaffected),
`GatewaySecurityKit`, `WebSocketKit` and `StaticContentKit`, all in the `r7-server` test jar.

## On Níma specifically

- **Request-head timeout.** Níma has none of its own. `HeadTimeouts` enforces
  `request_parse_timeout` on both listeners.
- **Header and body limits** are checked by `BlockingGateway` itself (431 and 413), since a
  server's own limits need not match `server.yaml`.
- **I/O pollers.** `R7Helidon.main` sets `jdk.pollerMode=1` (platform-thread pollers), or the
  mode in `R7_POLLER_MODE`, unless the property is already set. With virtual-thread or
  per-carrier pollers, new connections starved for seconds at saturation. See
  [`docs/performance_tuning.md`](../docs/performance_tuning.md#latency-at-saturation-the-jdks-io-pollers).
