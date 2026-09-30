# A server SPI: moving the pipeline out of `r7-undertow`

**Status:** proposed. Step 1 (measurement) is done; see "Measured". Nothing else here changes the
request path until it has a number behind it.

**Why.** `r7-api`, `r7-core`, `r7-utils` and both journal modules have no `io.undertow` or
`org.xnio` import, so filters, predicates, config and journaling are already server-neutral at
compile time. The lock-in is concentrated in `R7UndertowHandler` (about 1,100 lines), and most of
that file is not Undertow code: it is the request pipeline of `docs/config.md` §3, written
against `HttpServerExchange`. A second server (Helidon Níma is the candidate) built today would
have to copy it, and with it every security fix that has landed there (#89, #91), which would
then drift. Moving the pipeline into core behind a small SPI is worth doing on its own. It is also
what actually demonstrates the absence of lock-in; a second server then tests the SPI's shape.

---

## Measured

Profiled 2026-09-30: the gateway jar on six pinned cores (`-XX:+UseZGC`, JDK 25.0.4), nginx on
loopback, `wrk -t4 -c64` on separate cores, async-profiler in CPU mode for 15-20 s after
warm-up. At 113-123k req/s the whole process spends **~39 µs of CPU per passthrough request and
~47 µs at `HEADERS` journaling** (`/proc/<pid>/stat` delta over request count, two runs each).

Self time, by the deepest non-JDK frame of each sample:

| | passthrough | `HEADERS` journal |
|---|---|---|
| Kernel: socket syscalls, loopback TCP, nf_tables | 54% | 47% |
| Undertow / XNIO | 41% | 35% |
| r7 journal (`StatefulJournal`, `R7fJournal`, FlatBuffers) | 0.3% | **13.5%** |
| r7 everything else: guards, `open()`, routing, filters, metrics | **3.4%** | 3.0% |
| GC | not visible | not visible |

What this settles:

- **The layers and security checks are not the throughput problem.** Everything r7 does outside
  the journal is ~3% of I/O-thread CPU. `UpstreamHeaderSanitizer` is 0.1%, `RemoteAddressResolver`
  0.2%, `open()` 1.6% inclusive. The allocation list in `open()` (about 8.5 KB per request) costs
  a TLAB bump and dies young; GC does not show up in the profile at all.
- **Journaling is r7's one real cost:** +8 µs per request at `HEADERS`, about +20%. Within it,
  the header walks dominate: `RedactingHeaders.forEach` 5.1%, `ImmutableHeaderSnapshot.forEach`
  3.4%, `buildHeaderDelta` 3.2% (all inclusive), each through a lambda per header, plus
  `HeaderNameSet` hashing and case-insensitive compares. Monitor contention on
  `R7fJournal.writeEntry` is 1.4%.
- **The rest belongs to the server and the kernel.** A proxied request needs four socket
  syscalls: read from the client, write upstream, read the response, write to the client. JFR
  socket events show Undertow's client makes **five**: after every upstream response it reads
  once more and gets nothing. That read, `epoll_ctl` churn (2.8%) and read-timeout bookkeeping
  (~2%) are Undertow's and cannot be fixed from r7 without owning the proxy.

## The rule for the SPI

An interface with one implementation loaded in the process is close to free: the call site is
monomorphic and the JIT inlines it. So the SPI is held to **CPU per request not going up**,
checked with a profile and `benchmark/run.sh --repeat 3` before and after.

`RequestPathCostTest` reports CPU time per request on the I/O threads (not gated: it varies by a
factor of two between runs) and gates bytes allocated per request. The allocation gate is there
because it is stable enough for CI (within about 2%) and moves when a layer of per-request
objects is added; it is a tripwire, not a target.

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

How it avoids adding per-request objects:

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

1. **Measure.** Done: see "Measured", and `RequestPathCostTest` for the per-request numbers.
2. **Move the Undertow-free code:** status/DTO classes, `ServerConfig`, the guards once they take
   neutral types. Mechanical changes only.
3. **Introduce `ServerExchange` and `GatewayPipeline`;** `UndertowGatewayExchange` implements the
   SPI, with the dispatch index, commit and completion listeners and `PipelineState` held on the
   exchange rather than in lambdas and side objects. CPU per request stays flat.
4. **The security test kit.**
5. **Performance, where the profile points, one PR each:**
   - journal header encoding: walk each header set once, without a lambda per header, and hash
     names once per request rather than once per pass;
   - `R7fJournal.writeEntry` contention, if it grows with core count.

   Allocation work in `open()` (copy-on-write snapshots, interned header names, lazy
   attributes) is left out: at ~3% of CPU for all of r7 outside the journal, it cannot move
   throughput measurably.
6. **Helidon Níma spike**, as the second implementation of the SPI. It needs its own proxy
   layer; a basic HTTP/1.1 proxy on virtual threads is a few hundred lines, while parity with
   `ProxyHandler` (per-host pooling, host add/remove on health, selection strategies, timeouts,
   upstream TLS, X-Forwarded and Host rewriting, hop-by-hop stripping, `100-continue`,
   trailers, retries, WebSocket tunnelling) is more like 1.5–3k lines plus the test kit. The
   upside is that `UpstreamAbort`, `guardChunkedRequestBody` and `TransferEncodingGuard`, which
   exist to work around how Undertow's proxy frames bodies, become correct by construction.
   Post-1.0, experimental until the benchmark says otherwise.
