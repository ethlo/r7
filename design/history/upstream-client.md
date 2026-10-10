# One upstream client for every server: `r7-upstream`

> **History, not maintained.** This is the plan and measurements for `r7-upstream`, written while Undertow was still the default server (September to October 2026). Steps 1 to 5 shipped. Step 6 and the `proxy.client` setting became moot when Undertow was removed. How the upstream client works now is in [`../upstream.md`](../upstream.md).

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
  by reflection to report which target failed. Both break silently on an Undertow upgrade.
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
| https upstreams | `UndertowXnioSsl` | Done: `SSLSocket` from the JDK's default `SSLContext`, SNI and hostname verification on. |
| WebSocket / 101 | `ProxyHandler` tunnels | Done on Níma and the servlet host: after a 101 the relay hands the upstream connection to a `Tunnel`, which copies bytes both ways on two threads until either side closes. Not on Undertow with `r7` (see "WebSocket", below). |
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
  heap. Bound both.
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
2. **Harden the codec.** Done. Everything in "Things the spike gets wrong", with `Http1Test`
   (61 cases) for the parser and `UpstreamConformanceKit` (19 cases, a `ScriptedUpstream` per
   case) running against Níma and the servlet host. Response heads are bounded at 64 KB and 200
   lines by `UpstreamOptions`, not by the server's `max_header_size`: that limit is for clients,
   and Undertow's own client allowed upstream heads far larger than its 8 KB default. Idle
   pooled connections now expire after `proxy.ttl` (30 s, below nginx's 75 s keep-alive), since
   a request that fails on a stale connection is only retried when it is idempotent.

   The kit's first run found a bug the spike had on the servlet host: when the upstream's body
   failed part-way, the relay closed the client's body stream, and Tomcat completed the message
   with a terminating chunk - a truncated body delivered as a whole one. `ProxiedExchange`
   gained `abortResponse()`, which the thread-per-request servers honour by throwing out of the
   handler, so that the container drops the connection instead.

   Cost: about +3% user instructions and +2% cycles per passthrough GET, +1-2% per POST, at the
   edge of the ±1.5-2% noise floor; the `Connection` header is now tokenised without
   allocating. Still open: a blocking socket write has no timeout, so an upstream that stops
   reading a request body without closing holds the virtual thread until `max_request_time`
   exists to close the socket (step 5).
3. **Undertow adapter behind `proxy.client: r7`.** Done. `UndertowGatewayExchange` is a
   `ProxiedExchange`; `proxy()` dispatches the relay to the virtual-thread executor (or runs it
   inline when a filter already dispatched), and `ParkingChannelStreams` is the park/unpark
   bridge. The security kit and the conformance kit pass on Undertow with `r7` selected, and
   so does the whole `r7-undertow` suite (145 tests) with `r7` temporarily made the default -
   which caught one difference: `HttpUpstream` converted its timeouts to int milliseconds on
   first connect, where Undertow's client fails while the route generation is prepared. It now
   converts in its constructor. `X-Forwarded-Server` is added as Undertow adds it.

   `SlowPeerDescriptorTest` samples the process's epoll descriptors while 16 exchanges with a
   slow sender and a slow reader run. With the bridge replaced by `startBlocking()` streams it
   fails, with exactly one new epoll descriptor per exchange (47 → 63): the per-thread
   selectors are real, not just read off the bytecode. It samples during the run because by
   the end the test's allocation rate has let finalization close them again - which is also
   why this never shows in a benchmark.

   **Undertow's own client against the conformance kit** (run once, not kept as a test): 11 of
   19 fail. The ones that matter: a chunk longer than its declared size reaches the client as
   a complete response; a POST that fails on a reused connection is sent to the upstream a
   second time; conflicting `Content-Length`s, folded headers, a 70 KB header and
   `Transfer-Encoding: gzip, chunked` are all relayed; headers the upstream nominates in
   `Connection` leak to the client. The rest are status choices (503 where the kit expects
   502) and a stale pooled GET that is answered 503 rather than retried.

4. **Measure Undertow with `r7` against Undertow with `undertow`.** Done, and it does not pass
   the gate. Same jar, alternating JVMs, gateway pinned to P-cores 0-7, per request:

   | | req/s | user instr | kernel instr | cycles | context switches |
   |---|---|---|---|---|---|
   | browser GET, `undertow` | 80-94k | 255k | 115-127k | 231-252k | 0.53 |
   | browser GET, `r7` | 53-55k | 245k | 187-189k | 432-435k | 1.93 |
   | POST 1 KB, `undertow` | 91-104k | 143-146k | 102k | 166-168k | |
   | POST 1 KB, `r7` | 62-66k | 160k | 142-147k | 327k | |

   User-space work is level; the cost is the handoffs. Undertow's proxy never leaves the I/O
   thread. With a blocking client the request crosses threads at least three times - I/O
   thread to the relay's virtual thread, and back when `endExchange()` resumes reads for the
   next request, which wakes the I/O thread's selector from outside. That is the same cost a
   filter that `requiresDispatch()` pays, per request, and no tuning of the relay removes it.
   Níma has no such crossing: one virtual thread owns the whole request, which is why the same
   client is level with or ahead of Undertow there.

   The measurement also found two more ways Undertow's thread-local design breaks under a
   virtual thread per request, both fixed for `proxy.client: r7`:
   - `DefaultByteBufferPool` caches buffers per thread. Each new virtual thread took a global
     lock to register a cache in a list only the GC shrinks, and the buffers it freed were
     stranded; at load the lock stalled responses outright (POST fell to 0 req/s). With `r7`
     the server gets a pool with no thread-local cache. Before this fix user instructions per
     request were twice Undertow's; after it they are level.
   - A relay parked on a client that dropped its connection could wait on a channel wrapper
     that still reports open; the bridge now also checks the connection.

   So Undertow keeps its own client by default. Sharing one client with Undertow now means the
   non-blocking transport (the fallback under "The decision"), not a flag flip.

5. **Close the gaps.** Done; WebSocket under "Helidon parity", below. `HttpUpstreamClientTest`:
   - a target that refuses the connection is skipped for the next, once per target up;
   - `max_request_time` bounds the whole exchange, pool wait included: one daemon thread
     sweeps a registry of in-flight connections every 250 ms and closes the overdue ones,
     which also ends the write with no timeout that step 2 left open. Not a timer task per
     request - at 100k requests a second that is an allocation and a contended heap operation
     each, and routes come and go on hot reload with nothing to stop a thread of theirs;
   - per target, `connections_per_thread` x `io_threads` requests in flight (a semaphore) and
     `max_queue_size` waiting, then 503 with `ProxyPoolExhaustedException`;
   - https targets over `SSLSocket`, SNI and host-name verification on, the JVM's trust store.

   All of it maps from configuration in `UpstreamOptions.of`, the one place both Undertow and
   the thread-per-request servers read it. Per request on Níma against `main`: +2.5% user
   instructions and +0.5% cycles on GET, +6% and +1.6% on POST.

6. **Flip the default** to `r7` and delete what "What goes away" lists. The `undertow` value
   stays for one release as a way back. **Blocked** by step 4: not without a non-blocking
   transport for Undertow.

---

## Helidon parity, for the decision on switching servers

Undertow keeps its own client for now (step 4); whether r7 moves to Níma altogether is decided
later. Meanwhile Níma gains what it lacks, one PR each:

- **Management port.** Done. The dashboard and its JSON are `ManagementEndpoint` in `r7-server`,
  which Undertow's `StatusHandler` now adapts and Níma serves on a second listener
  (`putSocket`) with its own connection cap and idle timeout. `SimpleMetrics` and the
  `MetricsRegistry` moved to `r7-server` with it, so the filter works on every server.

  **Gap found, then closed from r7's side:** Níma has no request-head timeout. It reads through
  a `SocketChannel`, which ignores `SO_TIMEOUT`, and its idle sweep does not count a connection
  whose request line has arrived as idle - so a client trickling header lines held its
  connection indefinitely. `HeadTimeouts` enforces `request_parse_timeout` on both listeners:
  an `Http1ConnectionListener` starts a clock when the request line is read and stops it when
  the head is complete, and one sweeper thread ends overdue connections. Two more rough edges
  on the way: Helidon never calls the listener's per-read `data()` callbacks (only `prologue()`
  and `headers()`), and `ConnectionContext.serverSocket()` throws, so there is no handle to
  close. The sweeper interrupts the connection's reader thread instead - an interrupted read on
  an interruptible channel closes it - with a per-connection lock so the interrupt can never land
  once the head has completed and the request is using other sockets. A trickled *request line*
  never reaches the listener; Helidon counts it idle, and the management listener's idle sweep
  now runs every second instead of every two minutes so `idle_timeout` holds. Saturation
  throughput and tail unchanged (136-138k req/s, p99 4.4-4.7 ms, worst 17-24 ms).
- **Static content.** Done. `StaticFiles` (in `r7-server`) serves the `StaticContent` filter on
  Níma and the servlet host, and `StaticContentKit` (13 cases) holds all three servers to the
  same behaviour. Run against Undertow's `ResourceHandler` first, the kit found it serving files
  to `POST`, redirecting a directory without its trailing slash to the path *after*
  `StripPathPrefix` (off the route's prefix, so the client got a 404), and naming dotfiles in a
  listing while refusing to serve them. Undertow now decides those cases with `StaticFiles`
  before `ResourceHandler` sees the request; file serving itself stays Undertow's, zero-copy.
- **HTTP/2.** Done: h2c with `helidon-webserver-http2`, registered explicitly so that
  `http.enable_http2` (off by default) decides it, as on Undertow; the management listener is
  HTTP/1.1 only. The upstream hop stays HTTP/1.1, which makes the relay the place where an
  h2-to-h1 downgrade is either safe or a smuggling vector. It now refuses, with 400, what HTTP/2
  can carry and HTTP/1.1 cannot: a body whose length differs from its Content-Length (the excess
  never reaches the upstream), a header name that is not a token, and a value or target with a
  line break or other control character (`UpstreamRelayTest`). The header check costs about a
  tenth of the user instructions of a browser-like passthrough, so it runs only for requests
  that did not arrive over HTTP/1.x, whose parser already guarantees it; with it scoped, the
  branch is +4-5% user instructions and +0.5-1% cycles per request against `main` on Níma.
  `BlockingServerExchange` now carries the client's real protocol, which it had hard-coded as
  HTTP/1.1 - an HTTP/2 request would have been journaled as HTTP/1.1.
- **The stalls at saturation: explained, and gone.** The first benchmark showed Níma with
  outliers of 0.2-1.8 s at saturation (and two wrk timeouts) where Undertow's worst was
  ~50 ms. Narrowed down on the benchmark's own nginx, `wrk -c200`, gateway pinned to P-cores
  0-7, one variable at a time:
  - not the kernel: `nstat` showed ~170 new upstream connections/s, no listen drops, no SYN
    retransmits (the first hypothesis, pool churn past 64 idle connections, was wrong - with
    1024 idle the stall got no better);
  - not GC: ZGC reported no allocation stalls, pauses in microseconds; no safepoint over 20 ms;
  - not wrk or nginx: Undertow under identical conditions, worst 33-40 ms;
  - not Níma itself: a bare Helidon "OK" server did 278k req/s with a worst of 25 ms.

  JFR then showed the stalled connections were idle *between* requests, several resuming in
  the same millisecond - one shared thing held them. In JDK 27 the I/O pollers run as virtual
  threads on the same carriers as the requests (`Poller$VThreadsPollerGroup`); when the
  carriers are saturated a poller can wait long for one, and every connection registered with
  it waits too. Bare Helidon does too little per request to saturate carriers the same way.

  | `jdk.pollerMode` | req/s | p99 | worst |
  |---|---|---|---|
  | 2, virtual-thread pollers (JDK default) | 134-136k | 8.6-9.7 ms | 1.56-2.23 s |
  | 1, platform-thread pollers | 128k | 5.9 ms | 58 ms |
  | 3, per-carrier pollers | 132-137k | 4.4 ms | 16-25 ms |

  On **Java 25 with Helidon 4.5.5** - the LTS pairing; Helidon 27 is the Java 27 line and
  Helidon 29 will be the next LTS - the same code builds and passes all 54 Helidon tests
  unchanged (it is the default build; `-Phelidon-27` builds the Java 27 line), but mode 3
  does not exist: JDK 25's poller refuses the value and fails to start.
  Measured there at saturation:

  | Java 25 + Helidon 4.5.5 | req/s | p99 | worst |
  |---|---|---|---|
  | mode 2 (JDK default) | 141k | 7.0 ms | 539 ms |
  | mode 1, 2 read pollers (JDK default count) | 125-127k | 33-50 ms | 275 ms |
  | mode 1, 4 read pollers | 128k | 14.8 ms | 104 ms |
  | mode 1, 8 read pollers | 125k | 38.5 ms | 148 ms |

  Platform-thread pollers end the long stalls but compete with the carriers for the cores, and
  the p99 pays for it. So on 25 there is no setting that is both; the clean fix is the JDK's
  per-carrier poller.

  `R7Helidon.main` sets mode 3 on JDK 27 and later unless the property is set, and leaves
  JDK 25 on its default: the stalls occur only at saturation (the rate sweep below it showed
  none), and trading p99 at every load for them is the worse deal. It is an internal,
  undocumented JDK property: a future JDK may change or drop it, and an embedder that does not
  go through `main` must set it itself. With it, Níma's tail beats Undertow's (p99 5.9 ms, worst
  33-40 ms) at higher throughput.

  A second, separate contention showed on the static route: every file served opens a
  `FileChannel`, which registers with the JDK's `Cleaner` under one global lock, and ~50
  virtual threads queued on it (worst 4 s with the default pollers, 255 ms with mode 3). The fix
  is not to open a file per request for small hot files - a bounded content cache validated by
  size and modification time - and is still to do.
- **New connections starved at saturation (2026-10-10).** Mode 3 left one stall: wrk runs on a
  gateway pinned to 2 cores showed bursts of responses later than 5 s (104 and 86 of 200
  connections), all at the start of a run. wrk counts a response as a timeout when it arrives
  after `--timeout`, and does not count one that never arrives, so these were late, not lost.
  Reproduced with `wrk -c1000`: a probe sent straight to nginx was never slow, one through the
  gateway took 11-20 s, and a thread dump showed 569 unnamed virtual threads that had never run
  (wrk reported 570 timeouts). Helidon's acceptor is a platform thread, so each new
  connection's thread starts in ForkJoinPool's shared submission queue. Under mode 3 every
  wake-up goes to the carrier's own queue, and `topLevelExec` drains that before scanning
  anything else, so at saturation the new threads wait. Runs with a burst: mode 3 8 of 29,
  mode 2 1 of 8, mode 1 0 of 24. The pool's 64-idle cap was ruled out again: it churns ~100
  connections/s at 200 clients, and lifting it changed neither throughput nor the stalls.
  `R7Helidon.main` now defaults to mode 1, and `R7_POLLER_MODE` chooses another.
- **Header and body limits.** Done. `limits.max_header_size`, `max_header_count` and
  `max_entity_size` now hold on every server, checked by `GatewaySecurityKit` (seven cases, a
  head and a body at the limit included). Níma bounds the request line and the header fields
  separately (`maxPrologueLength`, `maxHeadersSize`) and the body as it arrives
  (`maxPayloadSize`), but has no header count limit; a servlet container has settings of its
  own, which need not match the operator's `server.yaml`. So `BlockingGateway` checks all three
  itself before the pipeline runs: 431 for a head over either limit, 413 for a declared body
  over `max_entity_size`, and a chunked body is held to it as it streams. Helidon fails a body
  read past `maxPayloadSize` with its own unchecked exception, which reached the relay as an
  unexpected error and a 500; the Níma adapter now maps it to r7's `RequestBodyTooLargeException`,
  and the relay answers 413.
- **Listener statistics.** Done. The dashboard's connector counters come from
  `ListenerStatistics`, counted the way Undertow's `ConnectorStatistics` counts: an error is a
  500, processing time runs from arrival to completion, bytes are the exchange's head and body.
  Níma keeps no connection counters it shares, but every connection runs inside one
  `ServerConnection.handle` call, so `CountingSelector` wraps the protocol selectors a listener
  derives from its protocols - a listener tries the selectors it is given first - and brackets
  that call. A servlet container's connections are its own; there they read 0.
- **Container image.** Done, and since folded in: with Undertow removed, `Dockerfile.jvm`
  builds the Helidon gateway as `r7-gateway`.
- **WebSocket.** Done. A 101 from the upstream is relayed with `Upgrade` and `Connection`
  (hop-by-hop, so set for the client's hop, not copied) and the handshake headers, and the
  upstream connection becomes a `Tunnel` (r7-upstream): untracked from `max_request_time`, its
  pool slot released - it is no longer a request - and its read timeout lifted. Bytes are copied
  uninterpreted, client to upstream on the server's thread and back on a virtual thread, and
  either side ending ends both: after the closing handshake the server side closes TCP first
  (RFC 6455 §7.1.1), and a tunnel that waited for a half-close to complete would hold a thread
  and two sockets for a peer that never closes. The upstream's first frames can arrive in the
  same read as its 101, so the tunnel reads through the head's buffer. A 101 for a request that
  did not ask to upgrade is refused with 502, and its bytes never reach the client.

  The server has to give up the client connection, which neither does from a routed handler.
  On Níma, `WebSocketUpgrader` is an `Http1Upgrader` for `websocket` - the public SPI Helidon's
  own WebSocket module uses - registered by r7 rather than found on the class path; it takes the
  handshake before routing and runs the whole exchange over the raw connection
  (`UpgradeExchange`), so a handshake that is not switched is answered with
  `Connection: close`. Helidon routes an upgrade with a body normally, and matches the Upgrade
  value exactly; those requests reach the ordinary handler, which cannot switch, and a 101 for
  them is answered 502. On a servlet container it is `HttpServletRequest.upgrade`, with the
  tunnel started from `HttpUpgradeHandler.init` on a thread of its own. Tomcat's
  `WebConnection.close()` only marks the streams closed and closes the socket on the next event
  it dispatches, which a blocking stream never asks for: a client waiting for the server to close
  after the closing handshake would wait for the connection timeout. Setting a write listener
  makes Tomcat dispatch a write event at once, and closing the output from it closes the socket.

  The journal's end of the exchange and the active-WebSocket gauge wait for the tunnel to end,
  as they wait for the connection to close on Undertow; the bytes tunnelled count as the
  exchange's body bytes. `WebSocketKit` (r7-server test-jar, six cases) holds Níma, the servlet
  host and Undertow's own client to the same behaviour; Undertow refuses an unasked 101 with 503
  rather than 502. Undertow with `proxy.client: r7` still answers a 101 with 502: its client
  connection would need the same bridge as the relay's body copies.

HTTP/3 is out: none of the servers, and not the JDK, has an HTTP/3 server.

