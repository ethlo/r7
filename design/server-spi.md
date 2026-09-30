# A server SPI: moving the pipeline out of `r7-undertow`

**Status:** proposed. Step 1 (measurement) comes first; nothing here changes the request path until
it has a number behind it.

**Why.** `r7-api`, `r7-core`, `r7-utils` and both journal modules have no `io.undertow` or
`org.xnio` import, so filters, predicates, config and journaling are already server-neutral at
compile time. The lock-in is concentrated in `R7UndertowHandler` (about 1,100 lines), and most of
that file is not Undertow code: it is the request pipeline of `docs/config.md` §3, written
against `HttpServerExchange`. A second server (Helidon Níma is the candidate) built today would
have to copy it, and with it every security fix that has landed there (#89, #91), which would
then drift. Moving the pipeline into core behind a small SPI is worth doing on its own. It is also
what actually demonstrates the absence of lock-in; a second server then tests the SPI's shape.

---

## The constraint: the SPI adds no allocations

An interface with one implementation loaded in the process is close to free: the call site is
monomorphic and the JIT inlines it. What layering costs is *objects*: adapters wrapping
adapters, capturing lambdas, side records. So the rule for this SPI is:

> Bytes allocated per request in `passthrough` must not go up. The refactor should bring them
> down.

This is enforced by a test, not by review (see step 1), because allocation per request is far
less noisy than req/s and can gate CI.

### Where the per-request cost actually is (from reading, not yet profiled)

The security guards are cheap. `RequestPathGuard` is one pass over the path with no allocation,
`TransferEncodingGuard` is one header lookup, TRACE is one comparison, and
`UpstreamHeaderSanitizer` uses Undertow's fast iterator. What is paid on *every* request,
including passthrough with no filters and journaling off:

| Cost | Where |
|---|---|
| Every header copied eagerly into `ImmutableHeaderSnapshot`, only because `sanitize()` edits the live headers afterwards | `open()` |
| About 10 objects: `UndertowGatewayRequest`, `RemoteInfo`, `ImmutableGatewayRequest`, `UndertowQueryParams`, `UndertowMutableCookies`, `UndertowGatewayResponse`, `FastGatewayAttributes`, `UndertowGatewayExchange`, `StatefulJournal` + `FingerprintMemo`, `OpenedExchange` | `open()` |
| Two capturing lambdas registered as listeners | `registerResponseListeners`, `setupCompletionHandler` |
| `new HttpString(name)` per `getFirst(String)`, then a linear scan | `ImmutableHeaderSnapshot` |
| `Optional…map…ifPresent`, `List.of(attemptedUris)` | `tagExchangeAttributes` |
| Undertow's `ProxyHandler` + `LoadBalancingProxyClient`, wrapped by `DiagnosticProxyClient` | Likely the largest share of passthrough-vs-baseline, and the one part not ours to tune |

The last row makes owning the proxy layer a performance argument as well as a lock-in argument.

---

## The boundary

### Core (`r7-core`, `com.ethlo.r7.server`)

```java
/** Server-neutral request pipeline, docs/config.md §3. One instance per server; no per-request state. */
public final class GatewayPipeline
{
    public void handle(final ServerExchange ex)             // guards → route → open → request filters
    public void resume(final ServerExchange ex)             // after dispatch; the index lives on the exchange
    public void onResponseCommit(final ServerExchange ex)   // upstream response snapshot, WS decision, response onion
    public void onComplete(final ServerExchange ex)         // handleCompleted + completed onion
}
```

### Server: one object per request

```java
/**
 * Implemented by the server's per-request object, which is also the GatewayExchange handed
 * to filters. One object, not an adapter wrapping another: the SPI must not add allocations.
 */
public interface ServerExchange extends ClientRequestGatewayExchange, UpstreamRequestGatewayExchange,
                                        ClientResponseGatewayExchange, CompletedGatewayExchange
{
    // Request as the server parsed it
    String method(); String protocol(); String decodedPath(); String rawUri();
    MutableGatewayHeaders liveRequestHeaders();     // what goes upstream; the sanitizer edits this
    InetSocketAddress peerAddress();

    // Threading
    boolean isOnIoThread();                         // Helidon: always false
    void dispatchResume(int nextFilterIndex);       // Undertow: exchange.dispatch(vtExecutor, this)

    // Answering locally
    void respond(int status, ByteBuffer body);      // headers already on clientResponse()
    void serveStatic(StaticServeRequest request);
    void closeAfterResponse();

    // Journal taps, only called at FULL
    void teeRequestBody(BodySink sink);
    void teeResponseBody(BodySink sink);

    // Hand-off; the server calls pipeline.onResponseCommit / onComplete
    void proxy(UpstreamHandle upstream);

    // Pipeline bookkeeping, held as fields on the exchange rather than in a side object
    PipelineState pipelineState();                  // route, journal, filter index, reason filter, timestamps
}

/** Per route, built by the server from UpstreamConfig; health monitoring stays in core. */
public interface UpstreamConnector
{
    UpstreamHandle open(GatewayRoute route, UpstreamConfig config, ProxyConfig proxy);
}
public interface UpstreamHandle extends UpstreamTargetObserver, AutoCloseable { }
```

How it keeps allocation at zero or below:

- **`dispatchResume` reuses the exchange.** The index is stored on the exchange and the exchange
  implements `Runnable`, so no capturing lambda is created. Helidon already runs each request on
  a virtual thread and simply continues inline.
- **Commit and completion listeners are singletons.** They are stateless and read the exchange
  from its attachment, so registering them allocates nothing.
- **`PipelineState` is fields on the exchange.** It absorbs `OpenedExchange`, `RemoteInfo`, the
  journal reference and the timestamps, removing objects rather than adding them.

### What moves where

| Now in `r7-undertow` | Goes to |
|---|---|
| `TransferEncodingGuard`, TRACE refusal, `RequestPathGuard`, `RemoteAddressResolver`, `UpstreamHeaderSanitizer` | core, over `GatewayHeaders` and plain strings instead of `HeaderMap`/`HttpString` |
| `handleRequest`, `open`, `refuseBeforeRouting`, `executeRequestFilters`, fallback routing, `Authorization` stripping, `shortCircuit`, response and completed onions, `handleCompleted`, `tagExchangeAttributes`, WebSocket detection, `setupJournaling` decisions | core `GatewayPipeline` |
| `RouteUpstreamContext` and health-monitor wiring | core; it holds an `UpstreamHandle` |
| `ServerConfig` (no Undertow imports) and the ~25 status/metrics/DTO classes (no Undertow imports) | core, or a new `r7-status` module |
| Proxy, pooling, TLS, conduits, `UpstreamAbort`, `guardChunkedRequestBody`, `DiagnosticProxyClient`, static `ResourceHandler`, `StatusHandler`/`TrafficMetricsHandler` | stay with the server |

`R7UndertowHandler` shrinks to roughly 200 lines of glue.

### The security checks need a shared test kit

The guard code lives in core, but whether it is *sufficient* depends on the server's parser:
Undertow accepts `chunked, identity`, and Helidon will have its own quirks. The smuggling and
path tests therefore become an abstract test kit in a test-jar that every server module runs:
`TransferEncodingGuardTest`, `RequestPathGuardTest`, `UpstreamHeaderHygieneTest`,
`WebSocketUpgradeOutcomeTest`, `RequestSizeLimitStreamingTest`, `R7FullSpecMatrixTest`. The
`r7.test.mode` switch is most of the way there.

---

## Order

Each step is its own PR. Steps 3 and 5 carry `benchmark/run.sh --repeat 3` before and after,
with `environment.txt`.

1. **Measure.** Profile `passthrough` (CPU and allocation). Add an allocated-bytes-per-request
   gate: N requests in-process, `com.sun.management.ThreadMXBean.getTotalThreadAllocatedBytes()`
   before and after. This establishes whether the cost is the layers, the copying or Undertow's
   proxy before anything is optimized.
2. **Move the Undertow-free code:** status/DTO classes, `ServerConfig`, the guards once they take
   neutral types. Mechanical changes only.
3. **Introduce `ServerExchange` and `GatewayPipeline`;** `UndertowGatewayExchange` implements the
   SPI. Allocation per request stays flat or drops.
4. **The security test kit.**
5. **Performance, one PR each:**
   - copy the client-request snapshot on first write, via the header adapter, instead of eagerly;
   - resolve header names to interned tokens at config load, so predicate lookups do not allocate;
   - allocate attributes only when something uses them;
   - a fast path for routes with no filters and journal `NONE`.
6. **Helidon Níma spike**, as the second implementation of the SPI. It needs its own proxy
   layer; a basic HTTP/1.1 proxy on virtual threads is a few hundred lines, while parity with
   `ProxyHandler` (per-host pooling, host add/remove on health, selection strategies, timeouts,
   upstream TLS, X-Forwarded and Host rewriting, hop-by-hop stripping, `100-continue`,
   trailers, retries, WebSocket tunnelling) is more like 1.5–3k lines plus the test kit. The
   upside is that `UpstreamAbort`, `guardChunkedRequestBody` and `TransferEncodingGuard`, which
   exist to work around how Undertow's proxy frames bodies, become correct by construction.
   Post-1.0, experimental until the benchmark says otherwise.
