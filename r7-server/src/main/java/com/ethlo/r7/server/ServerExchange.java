package com.ethlo.r7.server;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.CompletedGatewayExchange;
import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.GatewayResponse;
import com.ethlo.r7.api.GatewayRouteInfo;
import com.ethlo.r7.api.MutableGatewayAttributes;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.MutableGatewayRequest;
import com.ethlo.r7.api.MutableGatewayResponse;
import com.ethlo.r7.api.ShortCircuitGatewayResponse;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.config.DefaultGatewayRoute;
import com.ethlo.r7.filters.StaticContentFactory;
import com.ethlo.r7.journal.StatefulJournal;
import com.ethlo.r7.status.TrafficMetrics;
import com.ethlo.r7.time.ClockSource;

/**
 * One request, as the pipeline sees it: the exchange handed to every filter, the pipeline's
 * bookkeeping for it, and the handful of things only the HTTP server can do.
 * <p>
 * A server implements this once, as its own per-request object - one object, not an adapter
 * wrapping another, because what layering costs on the request path is objects, not calls: with
 * one server loaded, every abstract method here has one implementation and the JIT inlines it.
 * The pipeline's state is plain fields for the same reason; there is no side object holding it.
 * <p>
 * The hooks fall into three groups: the request as the server parsed it; answering, teeing and
 * proxying; and the two listener registrations through which the server hands the exchange back
 * to the pipeline when the response commits and when the exchange completes. Request hygiene -
 * framing, hop-by-hop and forwarding headers, the client address - is the pipeline's, applied to
 * what the server exposes here, so every server gets the same checks.
 */
public abstract class ServerExchange implements ClientRequestGatewayExchange, UpstreamRequestGatewayExchange, ClientResponseGatewayExchange, CompletedGatewayExchange, Runnable
{
    private final GatewayPipeline pipeline;

    // Set by GatewayPipeline.open(), once a route is chosen (or the unrouted section is used).
    String requestId;
    DefaultGatewayRoute route;
    // The route whose response and completed filters run: route, or the fallback it handed to.
    DefaultGatewayRoute responseRoute;
    GatewayRequest clientRequest;
    MutableGatewayResponse clientResponse;
    MutableGatewayAttributes attributes;
    StatefulJournal journal;

    // Who the request is attributed to, and whether its peer may make forwarding claims.
    RemoteAddressResolver.RemoteInfo remote;
    // The live request: what the predicates match, the filters change and the proxy forwards.
    MutableGatewayRequest liveRequest;
    MutableGatewayRequest upstreamRequest;
    GatewayResponse upstreamResponse;

    ShortCircuitGatewayResponse shortCircuitResponse;
    GatewayFilter reasonFilter;
    boolean webSocketRequested;
    boolean webSocketUpgraded;
    long proxyStartTs = -1;
    long journalBytes;

    // Where executeRequestFilters resumes after a dispatch; see run().
    DefaultGatewayRoute resumeRoute;
    int resumeIndex;

    protected ServerExchange(final GatewayPipeline pipeline)
    {
        this.pipeline = pipeline;
    }

    /**
     * Runs the filter that asked for a dispatch, and the rest of the chain after it, on the thread
     * the server dispatched to. The exchange is its own task so that a dispatch allocates nothing.
     */
    @Override
    public final void run()
    {
        this.pipeline.resumeDispatched(this);
    }

    public final GatewayPipeline pipeline()
    {
        return this.pipeline;
    }

    /**
     * Who the request is attributed to. Set before {@link #openLiveRequest()} is called.
     */
    protected final RemoteAddressResolver.RemoteInfo remoteInfo()
    {
        return this.remote;
    }

    // --- The request as the server parsed it -------------------------------------------------

    /**
     * The request method, before any filter has changed it.
     */
    protected abstract String method();

    /**
     * The request path as the server decoded it, which is what route predicates match against.
     */
    protected abstract String decodedPath();

    /**
     * When the server started reading this request, on the {@link System#nanoTime()} clock.
     */
    protected abstract long requestStartNanos();

    /**
     * The live request headers: what the proxy will copy upstream. Names must match ignoring
     * case. The pipeline checks their framing and removes hop-by-hop and untrusted forwarding
     * headers from them before any filter runs.
     */
    protected abstract MutableGatewayHeaders requestHeaders();

    /**
     * The address of the connection's immediate peer, or {@code null} if the server does not
     * know it.
     */
    protected abstract InetSocketAddress peerAddress();

    /**
     * The live request, attributed to {@link #remoteInfo()}. Called once per exchange, before
     * routing.
     */
    protected abstract MutableGatewayRequest openLiveRequest();

    /**
     * An immutable copy of the request as it arrived, for filters and the journal, attributed to
     * {@link #remoteInfo()}. Taken before the pipeline sanitises the live headers.
     */
    protected abstract GatewayRequest snapshotClientRequest();

    /**
     * The response as the server will send it: status and headers are read and written live.
     */
    protected abstract MutableGatewayResponse openClientResponse();

    /**
     * An immutable copy of the response headers and status as they are now; called when the
     * upstream's response commits.
     */
    protected abstract GatewayResponse snapshotResponse();

    // --- Threading ---------------------------------------------------------------------------

    /**
     * Whether a filter may block the calling thread: false on an event loop's I/O thread
     * (Undertow), true on a thread of the request's own (a Níma virtual thread, a servlet
     * container's request thread, or a thread {@link #resumeOnBlockingThread} moved it to).
     */
    protected abstract boolean mayBlock();

    /**
     * Hands this exchange to a thread where {@link #mayBlock()} holds, which runs it
     * ({@link #run()}); the calling thread returns at once. Called only when {@link #mayBlock()}
     * is false.
     */
    protected abstract void resumeOnBlockingThread();

    // --- Answering, teeing, proxying ---------------------------------------------------------

    /**
     * Sends {@code body} as the whole response body; status and headers are already on
     * {@link #clientResponse()}.
     */
    protected abstract void sendBody(ByteBuffer body);

    /**
     * Serves a file for a static content route; headers set so far stay.
     */
    protected abstract void serveStatic(StaticContentFactory.StaticServeRequest request);

    /**
     * Stops the connection being reused once this response is sent.
     */
    protected abstract void closeConnectionAfterResponse();

    /**
     * Passes every chunk of the request body, as it is read, to {@code sink}.
     */
    protected abstract void teeRequestBody(Consumer<ByteBuffer> sink);

    /**
     * Passes every chunk of the response body, as it is written, to {@code sink}.
     */
    protected abstract void teeResponseBody(Consumer<ByteBuffer> sink);

    /**
     * Hands the request to {@code upstream}. The server calls back through the listeners
     * registered by {@link #listenForCommit()} and {@link #listenForCompletion()}.
     */
    protected abstract void proxy(UpstreamHandle upstream) throws Exception;

    /**
     * The upstream targets tried for this request, in order; empty if none was.
     */
    protected abstract String[] attemptedUpstreams();

    /**
     * Runs {@code listener} when the client connection closes; for an upgraded websocket, that is
     * when the exchange really ends.
     */
    public abstract void onConnectionClose(Runnable listener);

    /**
     * Byte counts for the request and response as sent on the wire.
     */
    public abstract TrafficMetrics trafficMetrics();

    // --- Listener registration ---------------------------------------------------------------

    /**
     * Makes the server call {@link GatewayPipeline#onResponseCommit(ServerExchange)} when the
     * response is about to commit.
     */
    protected abstract void listenForCommit();

    /**
     * Makes the server call {@link GatewayPipeline#completeJournal(ServerExchange)} and then
     * {@link GatewayPipeline#runCompletedFilters(ServerExchange)} when the exchange completes.
     */
    protected abstract void listenForCompletion();

    // --- The filter-facing exchange ----------------------------------------------------------

    @Override
    public String requestId()
    {
        return this.requestId;
    }

    @Override
    public GatewayRequest clientRequest()
    {
        return this.clientRequest;
    }

    @Override
    public MutableGatewayRequest upstreamRequest()
    {
        return this.upstreamRequest;
    }

    @Override
    public GatewayResponse upstreamResponse()
    {
        return this.upstreamResponse;
    }

    @Override
    public MutableGatewayResponse clientResponse()
    {
        return this.clientResponse;
    }

    @Override
    public MutableGatewayAttributes attributes()
    {
        return this.attributes;
    }

    @Override
    public GatewayRouteInfo route()
    {
        final DefaultGatewayRoute r = this.route;
        return new GatewayRouteInfo()
        {
            @Override
            public String id()
            {
                return r.id();
            }

            @Override
            public String toString()
            {
                return r.uri().toString();
            }
        };
    }

    @Override
    public void shortCircuit(final ShortCircuitGatewayResponse response)
    {
        this.shortCircuitResponse = response;
    }

    @Override
    public boolean isShortCircuited()
    {
        return this.shortCircuitResponse != null;
    }

    @Override
    public boolean wasProxied()
    {
        return this.proxyStartTs != -1;
    }

    // --- For metrics ------------------------------------------------------------------------

    /**
     * Whether the upstream actually answered {@code 101 Switching Protocols} for this exchange,
     * not merely whether the client asked to upgrade. False until the response commits, and for an
     * exchange with no upstream at all.
     */
    public boolean isWebsocketUpgraded()
    {
        return this.webSocketUpgraded;
    }

    public long getJournalBytes()
    {
        return this.journalBytes;
    }

    public long getDurationNanos()
    {
        return System.nanoTime() - requestStartNanos();
    }

    long requestStartEpochNanos()
    {
        return ClockSource.now() - (System.nanoTime() - requestStartNanos());
    }
}
