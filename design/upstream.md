# One upstream client for every server: `r7-upstream`

**Status:** proposed. Nothing here changes the request path until step 1 lands with instructions
per request unchanged, and Undertow's default does not move until step 4 has numbers behind it.

**Why.** r7 proxies with two clients today. Undertow uses its own `ProxyHandler` and
`LoadBalancingProxyClient`; Níma and the servlet host use `HttpUpstream`, the blocking HTTP/1.1
client in `r7-server-blocking` (design/server-spi.md, steps 6 and 7). Two clients means two sets
of framing rules, two sets of edge cases, and security fixes that have to land twice. Undertow's
client also has costs r7 cannot remove while Undertow owns it:

- **Reflection into Undertow internals.** `UpstreamAbort` reaches the socket through the
  package-private `HttpClientConnection.connection` field, because Undertow's "clean" close of a
  chunked upstream request writes the terminating `0\r\n\r\n` and hands the upstream a truncated
  body as a complete one. `DiagnosticProxyClient` reads `LoadBalancingProxyClient.ATTEMPTED_HOSTS`
  by reflection to report which target failed. Both break silently on an Undertow upgrade, and
  both need `R7ReflectionFeature` entries for the native image.
- **Syscalls r7 cannot drop.** The step 1 profile in server-spi.md: a proxied request needs four
  socket syscalls, and Undertow's client makes five; after every upstream response it reads once
  more and gets nothing. That, `epoll_ctl` churn (2.8% of CPU) and read-timeout bookkeeping (~2%)
  are the client's.
- **A workaround for its round robin.** `UpstreamHostSelectors.RoundRobin` exists because
  Undertow's own selector takes `counter % hosts`, which goes negative after $2^{31}$ requests.

A client r7 owns removes all three by construction: aborting is closing a socket r7 holds, the
attempted target is a field, and the read loop stops at the end of the framed body.

---

## The decision: blocking, on virtual threads, on every server

The upstream exchange runs on a virtual thread with plain `java.net.Socket` I/O, on Undertow as
well as Níma and the servlet host. It is one client, one threading model, and one set of tests.

The alternative was a sans-I/O codec with two transports, blocking for the thread-per-request
servers and XNIO non-blocking for Undertow. It keeps Undertow's event loop on the proxy path, at
the price of a second transport whose correctness has to be proven separately. It stays the
fallback if step 4 shows that dispatching every proxied request to a virtual thread costs
Undertow more than its own client does; the codec split in step 1 is what keeps that fallback a
transport-only job.

Why blocking is credible: Níma already runs the same client on virtual threads at level CPU per
request with Undertow (55-58 µs against 55 µs passthrough), with ~40% fewer user instructions and
~15% more kernel instructions, which is virtual-thread parking showing as `futex` time.

### Undertow's blocking streams are not usable from a virtual thread

The obvious way to relay on Undertow from a virtual thread is `exchange.startBlocking()` and its
`InputStream`/`OutputStream`. It does not work well, and it only fails under load, so it is easy
to miss in a benchmark. In XNIO 3.8.16 (disassembled; these are the call chains):

- `NioSocketConduit.awaitReadable()` / `awaitWritable()` call `SelectorUtils.await`, which
  registers the channel with a selector and calls **`Selector.select()`**. That is a native
  blocking call: on a virtual thread it holds the carrier thread rather than parking.
- The selector comes from `NioXnio.getSelector()`, a **`ThreadLocal`** of
  `FinalizableSelectorHolder`. Every virtual thread that ever has to wait opens a selector of its
  own (an epoll fd and a wakeup fd), potentially one per request, **closed only by
  finalization**. With `--finalization=disabled`, or finalization falling behind, those fds leak.

Both only trigger when a read or write would block: a slow client, a large or slowly sent body.
The fast path never waits, which is why a passthrough benchmark would pass.

So on Undertow the client side is bridged with the non-blocking channel API and park/unpark,
never with Undertow's blocking streams (see "The Undertow adapter").

---

## The module

`r7-upstream`, package `com.ethlo.r7.upstream`. It depends on `r7-api` and `r7-server` (for
`UpstreamHandle` and the proxy exceptions it reports) and names no server type. `r7-server-blocking`
and `r7-undertow` depend on it.

```
r7-api ← r7-core ← r7-server ← r7-upstream ← r7-server-blocking ← r7-helidon, r7-servlet
                                           ↖ r7-undertow
```

### Two layers

**Codec (`Http1`)**, which does no I/O: it encodes the upstream request head, decides framing,
parses and validates the upstream response head, and decodes chunked bodies as a state machine
over byte ranges. This is where request smuggling and response splitting are decided, so it
exists once and is tested exhaustively and in isolation. Step 1 moves the request head encoding
and framing into `Http1`; the response-head parser and the chunked decoder follow in step 2.

**Transport (`HttpUpstream`, `UpstreamRelay`)**: targets, strategy, the connection pool, sockets,
timeouts, and the loop that drives the codec between the upstream socket and the server's
streams.

### The contract with a server

The server supplies the request, the body streams and the response; the relay drives them. Teeing,
counting and the request-body limit stay with the server, which already does them at its own
layer: Undertow in its conduits, `BlockingServerExchange` in the two per-block callbacks, which
allocate nothing. The relay does not know journaling exists.

```java
/** Implemented by the server's exchange; everything the relay needs from the client side. */
public interface ProxiedExchange
{
    // The request as the pipeline leaves it: sanitised, filtered, ready to forward
    String forwardMethod();
    String forwardTarget();                 // raw path and query, as sent
    GatewayHeaders forwardHeaders();
    String forwardedFor();                  // the peer the gateway vouches for in X-Forwarded-For
    String forwardedProto();

    // Client side
    InputStream openRequestBody() throws IOException;     // only called when the request has a body
    default void onRequestBody(byte[] b, int off, int len) throws IOException { }  // count, limit, tee
    MutableGatewayResponse clientResponse();              // the relay sets status and headers here
    OutputStream commit(boolean body) throws IOException; // commit work, then the head; null when !body
    default void onResponseBody(byte[] b, int off, int len) { }                   // count, tee

    // Reporting
    void attemptedTarget(URI target);
}

public final class UpstreamRelay
{
    /** Runs the whole exchange on the calling thread, which must be allowed to block. */
    public static void relay(HttpUpstream upstream, ProxiedExchange exchange, String routeId) throws ProxyFailure;
}
```

The names avoid `ServerExchange`'s own (`method()`, `requestBody()`, ...), whose protected
declarations a public interface method of the same name would clash with.

`ProxyFailure` carries the status the client should get (502, 503, 504, 413) and the failure to
report to the pipeline, if any. The server answers with it unless its response has already
started, so error bodies stay identical across servers. A request body over the limit is
signalled by throwing `RequestBodyTooLargeException` from `onRequestBody`.

One object per request, as the server SPI requires: `ProxiedExchange` is implemented by the
server's exchange itself, not an adapter around it.

---

## What `HttpUpstream` has to gain

Undertow's client provides these today, so none of them is optional before step 5. Helidon and
the servlet host need them too, which is why they come before Undertow depends on the module.

| Gap | Today on Undertow | Plan |
|---|---|---|
| https upstreams | `UndertowXnioSsl` | `SSLSocket` from the JDK's default `SSLContext`, SNI and hostname verification on. |
| WebSocket / 101 | `ProxyHandler` tunnels | After a 101, hand both sockets to two virtual threads copying bytes until either side closes. The server adapter exposes the client connection's raw streams. |
| Pool limits and queueing | `connections_per_thread`, `max_queue_size` | A per-target semaphore, with waiters bounded by `max_queue_size`; beyond it, 503. |
| `ttl` | idle-connection TTL | Idle connections carry their release time and are closed on acquire when older than `ttl`. |
| `max_request_time` | whole-exchange deadline | A deadline checked between reads, plus the socket timeout set to the time remaining. |
| Connect timeout | Undertow default | Stays 5 s for now; a config field is its own change. |
| Retry to another target | `LoadBalancingProxyClient` retries a failed connect | Retry a *connect* failure on the next target, up to the number of targets. Never retry once any request byte was written, except as below. |
| 100-continue | handled | `Expect` is stripped on the way upstream today; the client is answered by the server. Keep, and test. |

### Things the spike gets wrong that the codec must fix

These were acceptable for an experimental server and are not acceptable for the default one. Each
gets a named test in step 2.

- **Response framing is parsed leniently.** `Long.parseLong` accepts `+1f` as a chunk size, and a
  negative size silently ends the body; a non-numeric `Content-Length` throws
  `NumberFormatException` *after* the response head was committed; several `Content-Length`
  values are not rejected; `Name : value` (whitespace before the colon) is trimmed and accepted,
  which RFC 9112 §5.1 says must be rejected. On a pooled connection each of these is a
  desynchronisation: the next request reads the rest of this response. Parse strictly, reject
  before committing, and never pool a connection whose response was not framed exactly.
- **Response lines have no length bound.** `LineReader` spills into a `StringBuilder` without
  limit, and there is no limit on header count; a hostile or broken upstream can exhaust the
  heap. Bound both, mirroring the server's own `max_header_size` and `max_header_count`.
- **`Connection: close` is compared as a whole value.** `Connection: keep-alive, close` is
  missed, and the headers a `Connection` value nominates are not stripped from the response. An
  HTTP/1.0 response without `keep-alive` is pooled as if it were persistent.
- **The retry replays non-idempotent requests.** A reused connection that fails is retried when
  the request had no body, including a `POST` or `DELETE` without one. If the upstream processed
  it and then closed, the retry runs it twice. Retry only when the failure came before the
  request was fully written, or for idempotent methods (RFC 9110 §9.2.2).
- **An early upstream response is lost.** The whole request body is written before the response
  is read, so an upstream that answers 413 part-way through and closes turns into a 502 with the
  upstream's answer lost. Read the response on a second virtual thread while the body is still
  being written, or at least attempt a read on write failure.

---

## The Undertow adapter

`UndertowGatewayExchange.proxy()` dispatches to the virtual-thread executor, where the relay runs.
The exchange implements `Runnable` for this, as it already does for filter dispatch, so the
hand-off allocates nothing.

Client-side I/O goes through Undertow's channels, so its conduits (the journal tees,
`RequestBodyGuardConduit`, traffic metrics) keep seeing every byte unchanged:

```
read:   n = channel.read(buf)
        n > 0  → deliver
        n == 0 → readSetter = unpark(thisThread); resumeReads(); park; suspendReads(); retry
        n < 0  → end of body
write:  same shape with write(), writeSetter and resumeWrites()
finish: shutdownWrites(); flush() until it returns true, parking the same way
```

The listeners run on the I/O thread and only unpark; the virtual thread owns the exchange for
the whole relay. In the common case (small bodies, a client that keeps up) no call returns zero
and nothing parks. This bridge is about 100 lines, and it is the one piece of new Undertow code.

A test sends a multi-megabyte body slowly and reads the response slowly, then asserts that the
process's open file descriptor count is back where it started. That test is the guard against
someone "simplifying" the bridge to `startBlocking()`.

### What goes away

Once Undertow's default is `r7`: `DiagnosticProxyClient`, `UpstreamAbort`,
`UpstreamHostSelectors`, the `guardChunkedRequestBody` special case (the relay aborts by closing
its own socket, so a truncated body can never be terminated cleanly), the `UndertowXnioSsl`
set-up, and their `R7ReflectionFeature` entries. `HttpStringUtil`'s `VarHandle` into
`HttpString.bytes` is about request headers and stays.

---

## Configuration

`ServerConfig.ProxyConfig` is Undertow-shaped. `connections_per_thread` has no meaning when
there are no I/O threads on the proxy path.

- Step 3 adds `proxy.client: undertow | r7`, default `undertow`, read only by `r7-undertow`.
- `connections_per_thread` is interpreted as `connections_per_thread × io_threads` per target
  by the r7 client, so an existing config means the same capacity on either client.
- A `max_connections_per_target` field, with `connections_per_thread` deprecated, is decided when
  the default flips; renaming config is a user-facing change and gets its own PR.

`docs/config.md` documents `proxy.client` as experimental in step 3.

---

## Testing

- **Codec, in isolation**: every rule in "Things the spike gets wrong" as a named test, plus a
  differential test: a generated corpus of response heads and chunked bodies, parsed by the codec
  and by a reference parser written from RFC 9112's grammar, which must agree on accept/reject
  and on the body boundary. The same mutant-driven approach as the sanitizer's differential test
  in server-spi.md step 4a.
- **Upstream-side kit**: `GatewaySecurityKit` today attacks the gateway from the client. Add
  `UpstreamConformanceKit`: a scripted raw-socket upstream that misbehaves (duplicate
  `Content-Length`, CL plus TE, truncated chunk, over-long header line, early response and close,
  HTTP/1.0 without keep-alive, a pooled connection closed while idle). It asserts what the client
  received *and* that the next request on the same route is not affected, which is the
  desynchronisation check. Each server module runs it by implementing one method, as with the
  security kit. It runs against Undertow's own client too: where Undertow's client fails a case,
  that is recorded, not fixed.
- **Fd-leak test** on Undertow, as above.

---

## Measured

**Benchmark, 2026-09-30:** Undertow against Níma, both on the gateway jar built with JDK 27, the
gateway pinned to P-cores 0-7 and wrk to E-cores 12-19, nginx in Docker unpinned, governor
`powersave`; `benchmark/run.sh --repeat 3 --restart-per-repeat`, 200 connections. This compares
the two *servers*, each with its own client, so it bounds what is at stake, not what the module
will do on Undertow.

Saturation throughput (wrk), req/s, medians of 3 fresh JVMs; nginx alone does ~274k:

| | Undertow | Níma | |
|---|---|---|---|
| passthrough browser | 131.8k (±7.0%) | 141.8k (±1.6%) | +8% |
| passthrough headers | 100.2k (±5.9%) | 113.0k (±2.0%) | +13% |
| passthrough post | 154.8k (±2.4%) | 163.4k (±0.3%) | +6% |
| filtered browser | 126.9k (±0.8%) | 136.7k (±1.1%) | +8% |
| filtered headers | 98.1k (±1.8%) | 110.8k (±0.5%) | +13% |
| filtered post | 149.6k (±0.7%) | 155.3k (±1.8%) | +4% |

p99 / p99.9 against offered load (wrk2, one run per rate, so read each cell as ±20%), ms:

| rate | Undertow browser | Níma browser | Undertow post | Níma post |
|---|---|---|---|---|
| 20k | 5.0 / 11.6 | 3.8 / 5.3 | 4.8 / 11.4 | 3.4 / 5.3 |
| 60k | 14.3 / 28.0 | 11.5 / 18.8 | 10.5 / 20.6 | 10.5 / 17.0 |
| 90k | 28.6 / 57.4 | 12.5 / 27.1 | 18.7 / 61.7 | 10.1 / 21.8 |
| 110k | 50.4 / 87.4 | 23.6 / 45.7 | 23.4 / 46.3 | 9.2 / 17.9 |
| 130k | 2850 / 3680 | 49.8 / 93.9 | 52.7 / 88.9 | 29.9 / 54.4 |

Below saturation Níma is ahead everywhere: 4-13% more throughput, and p99.9 about half of
Undertow's at every rate. The exception is saturation itself. Under wrk's open taps Níma's
*maximum* latency is 0.16-1.8 s against Undertow's 43-59 ms, and one `filtered headers` run had
two requests time out after 2 s (marked `INVALID`). A few requests wait a very long time when
Níma is overloaded, where Undertow degrades evenly. Not yet profiled; virtual-thread scheduling
fairness under overload is the first suspect. It has to be understood before Níma could be a
default.

These rows compare server *and* client together, so they do not say how much of the gain is the
upstream client. Step 4, Undertow with the r7 client against Undertow with its own, separates
the two.

---

## Order

Each step is its own PR, with instructions and cycles per request from `perf stat` before and
after (the method in server-spi.md, "Order"), and `benchmark/run.sh --repeat 3` as the final
check.

1. **Extract.** Create `r7-upstream`; move `HttpUpstream` and the proxy half of
   `BlockingServerExchange` into it behind `ProxiedExchange` and `UpstreamRelay`, with the codec
   separated from the transport. No behaviour change; Helidon and servlet instructions per
   request unchanged; security kit green on both.
2. **Harden the codec.** Everything in "Things the spike gets wrong", each with its test, and
   `UpstreamConformanceKit` running against Níma and the servlet host.
3. **Undertow adapter behind `proxy.client: r7`.** The channel bridge, dispatch, the fd-leak
   test, both kits green on Undertow with `r7` selected.
4. **Measure Undertow with `r7` against Undertow with `undertow`.** Instructions and cycles per
   request, then the throughput table and the wrk2 sweep above. If `r7` is worse, profile the
   dispatch and the parking before considering the non-blocking transport.
5. **Close the gaps**, one PR each: https, WebSocket tunnelling, pool limits and queue, `ttl` and
   `max_request_time`, connect-failure retry. Each adds its case to the conformance kit.
6. **Flip the default** to `r7` and delete what "What goes away" lists. The `undertow` value
   stays for one release as a way back.
