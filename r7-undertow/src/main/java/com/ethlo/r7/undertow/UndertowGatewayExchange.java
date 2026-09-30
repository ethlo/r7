package com.ethlo.r7.undertow;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

import org.xnio.IoUtils;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.GatewayResponse;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.MutableGatewayRequest;
import com.ethlo.r7.api.MutableGatewayResponse;
import com.ethlo.r7.api.StateKey;
import com.ethlo.r7.filters.StaticContentFactory;
import com.ethlo.r7.server.GatewayPipeline;
import com.ethlo.r7.server.RemoteAddressResolver;
import com.ethlo.r7.server.ServerExchange;
import com.ethlo.r7.server.UpstreamHandle;
import com.ethlo.r7.status.TrafficMetrics;
import com.ethlo.r7.status.TrafficMetricsHandler;
import com.ethlo.r7.upstream.HttpUpstream;
import com.ethlo.r7.upstream.ProxiedExchange;
import com.ethlo.r7.upstream.ProxyFailure;
import com.ethlo.r7.upstream.RequestBodyTooLargeException;
import com.ethlo.r7.upstream.UpstreamRelay;
import com.ethlo.r7.util.ImmutableGatewayRequest;
import com.ethlo.r7.util.ImmutableGatewayResponse;
import io.undertow.server.ExchangeCompletionListener;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.ResponseCommitListener;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;

/**
 * The Undertow side of one request: the {@link ServerExchange} filters see, backed by the
 * {@link HttpServerExchange} it arrived on.
 */
public class UndertowGatewayExchange extends ServerExchange implements ProxiedExchange
{
    private static final Object REGISTRY_LOCK = new Object();
    // Start with a reasonable size, it will grow automatically if needed
    private static volatile AttachmentKey<?>[] KEY_REGISTRY = new AttachmentKey<?>[32];

    static final AttachmentKey<Long> REQUEST_BODY_LIMIT = AttachmentKey.create(Long.class);

    /**
     * Stateless, so one instance serves every exchange: registering it costs no allocation, where
     * a lambda capturing the exchange would cost one per request.
     */
    private static final ResponseCommitListener COMMIT_LISTENER = serverExchange ->
    {
        final UndertowGatewayExchange ex = serverExchange.getAttachment(R7UndertowHandler.GATEWAY_EXCHANGE_KEY);
        ex.pipeline().onResponseCommit(ex);
    };

    /**
     * Stateless for the same reason. The completed filters run in a {@code finally} with the next
     * listener, as before, and the journal is completed ahead of them outside it.
     */
    private static final ExchangeCompletionListener COMPLETION_LISTENER = (serverExchange, next) ->
    {
        final UndertowGatewayExchange ex = serverExchange.getAttachment(R7UndertowHandler.GATEWAY_EXCHANGE_KEY);
        ex.pipeline().completeJournal(ex);
        try
        {
            ex.pipeline().runCompletedFilters(ex);
        }
        finally
        {
            next.proceed();
        }
    };

    private final HttpServerExchange undertowExchange;
    private final R7UndertowHandler handler;
    private MutableGatewayHeaders requestHeaders;

    // Only used with proxy.client: r7.
    private String attemptedTarget;
    private long relayedRequestBytes;

    UndertowGatewayExchange(final HttpServerExchange undertowExchange, final GatewayPipeline pipeline, final R7UndertowHandler handler)
    {
        super(pipeline);
        this.undertowExchange = undertowExchange;
        this.handler = handler;
    }

    // --- The request as Undertow parsed it ---------------------------------------------------

    @Override
    protected String method()
    {
        return this.undertowExchange.getRequestMethod().toString();
    }

    @Override
    protected String decodedPath()
    {
        return this.undertowExchange.getRequestPath();
    }

    @Override
    protected long requestStartNanos()
    {
        return this.undertowExchange.getRequestStartTime();
    }

    @Override
    protected MutableGatewayHeaders requestHeaders()
    {
        MutableGatewayHeaders headers = this.requestHeaders;
        if (headers == null)
        {
            headers = new UndertowGatewayHeaders(this.undertowExchange.getRequestHeaders());
            this.requestHeaders = headers;
        }
        return headers;
    }

    @Override
    protected InetSocketAddress peerAddress()
    {
        return this.undertowExchange.getSourceAddress();
    }

    @Override
    protected MutableGatewayRequest openLiveRequest()
    {
        final RemoteAddressResolver.RemoteInfo remote = remoteInfo();
        return new UndertowGatewayRequest(this.undertowExchange, remote.address(), remote.source());
    }

    @Override
    protected GatewayRequest snapshotClientRequest()
    {
        final HttpServerExchange exchange = this.undertowExchange;
        return new ImmutableGatewayRequest(exchange.getProtocol().toString(),
                new ImmutableHeaderSnapshot(exchange.getRequestHeaders()),
                exchange.getRequestPath(),
                exchange.getRequestURI(),
                exchange.getRequestMethod().toString(),
                new UndertowQueryParams(exchange.getQueryString(), exchange.getQueryParameters()),
                new UndertowMutableCookies(exchange),
                remoteInfo().address(),
                remoteInfo().source()
        );
    }

    @Override
    protected MutableGatewayResponse openClientResponse()
    {
        return new UndertowGatewayResponse(this.undertowExchange);
    }

    @Override
    protected GatewayResponse snapshotResponse()
    {
        final HttpServerExchange exchange = this.undertowExchange;
        return new ImmutableGatewayResponse(exchange.getProtocol().toString(), new ImmutableHeaderSnapshot(exchange.getResponseHeaders()), exchange.getStatusCode(), true);
    }

    // --- Threading ---------------------------------------------------------------------------

    @Override
    protected boolean mayBlock()
    {
        return !this.undertowExchange.isInIoThread();
    }

    @Override
    protected void resumeOnBlockingThread()
    {
        // Undertow handles the async hand-off. The IO thread returns as soon as this does and
        // goes back to accepting TCP connections.
        this.undertowExchange.dispatch(this.handler.virtualThreadExecutor(), this);
    }

    // --- Answering, teeing, proxying ---------------------------------------------------------

    @Override
    protected void sendBody(final ByteBuffer body)
    {
        this.undertowExchange.getResponseSender().send(body);
    }

    @Override
    protected void serveStatic(final StaticContentFactory.StaticServeRequest request)
    {
        this.handler.serveStatic(this.undertowExchange, request);
    }

    @Override
    protected void closeConnectionAfterResponse()
    {
        this.undertowExchange.setPersistent(false);
    }

    @Override
    protected void teeRequestBody(final Consumer<ByteBuffer> sink)
    {
        this.undertowExchange.addRequestWrapper((factory, ex) -> new TeeingStreamSourceConduit(factory.create(), sink));
    }

    @Override
    protected void teeResponseBody(final Consumer<ByteBuffer> sink)
    {
        this.undertowExchange.addResponseWrapper((factory, ex) -> new TeeingStreamSinkConduit(factory.create(), sink));
    }

    @Override
    protected void proxy(final UpstreamHandle upstream) throws Exception
    {
        if (upstream instanceof HttpUpstream r7)
        {
            // The relay blocks, so it runs on a virtual thread: already on one if a filter
            // dispatched, otherwise dispatched now, and the I/O thread returns at once. A
            // Runnable given to dispatch() runs as it is, outside Undertow's "in call" state, so
            // the resumeReads/resumeWrites the bridge makes take effect immediately.
            if (mayBlock())
            {
                relay(r7);
            }
            else
            {
                this.undertowExchange.dispatch(this.handler.virtualThreadExecutor(), () -> relay(r7));
            }
            return;
        }
        guardChunkedRequestBody(this.undertowExchange);
        ((R7UndertowHandler.UndertowUpstream) upstream).proxyHandler().handleRequest(this.undertowExchange);
    }

    /**
     * One exchange through the r7 client, on a thread that may block. Nothing escapes: after a
     * dispatch there is no pipeline frame left to catch it, so failures are answered and
     * reported here, the way the pipeline does it for a filter.
     */
    private void relay(final HttpUpstream upstream)
    {
        try
        {
            UpstreamRelay.relay(upstream, this, route().id());
        }
        catch (final ProxyFailure failure)
        {
            respondError(failure.status(), failure.message());
            if (failure.getCause() != null)
            {
                this.handler.errorHandler().handleError(this, failure.getCause());
            }
        }
        catch (final RuntimeException e)
        {
            // Fail closed.
            if (this.undertowExchange.isResponseStarted())
            {
                abortResponse();
            }
            else
            {
                respondError(500, "Internal gateway error");
            }
            this.handler.errorHandler().handleError(this, e);
        }
    }

    private void respondError(final int status, final String message)
    {
        if (this.undertowExchange.isResponseStarted())
        {
            return;
        }
        this.undertowExchange.setStatusCode(status);
        this.undertowExchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain; charset=utf-8");
        this.undertowExchange.getResponseHeaders().put(Headers.X_CONTENT_TYPE_OPTIONS, "nosniff");
        this.undertowExchange.getResponseSender().send(message);
    }

    // --- ProxiedExchange: the client side of the r7 upstream client ---------------------------

    @Override
    public String forwardMethod()
    {
        return this.undertowExchange.getRequestMethod().toString();
    }

    /**
     * The request target as Undertow's ProxyHandler builds it: the request URI - encoded, as
     * filters leave it - without the scheme and authority of an absolute-form target, and the
     * query string.
     */
    @Override
    public String forwardTarget()
    {
        String uri = this.undertowExchange.getRequestURI();
        if (this.undertowExchange.isHostIncludedInRequestURI())
        {
            final int authority = uri.indexOf("//");
            if (authority != -1)
            {
                final int path = uri.indexOf('/', authority + 2);
                if (path != -1)
                {
                    uri = uri.substring(path);
                }
            }
        }
        final String query = this.undertowExchange.getQueryString();
        return query == null || query.isEmpty() ? uri : uri + '?' + query;
    }

    @Override
    public GatewayHeaders forwardHeaders()
    {
        return requestHeaders();
    }

    @Override
    public String forwardedFor()
    {
        final InetSocketAddress peer = this.undertowExchange.getSourceAddress();
        if (peer == null)
        {
            return "unknown";
        }
        return peer.getAddress() != null ? peer.getAddress().getHostAddress() : peer.getHostString();
    }

    @Override
    public String forwardedProto()
    {
        return "https".equals(this.undertowExchange.getRequestScheme()) ? "https" : "http";
    }

    @Override
    public InputStream openRequestBody()
    {
        return ParkingChannelStreams.requestBody(this.undertowExchange);
    }

    @Override
    public void onRequestBody(final byte[] buffer, final int offset, final int length) throws IOException
    {
        // The journal tee and the traffic metrics see the body in their conduits; only the
        // route's limit is left to enforce, which RequestBodyGuardConduit does for Undertow's
        // own client.
        this.relayedRequestBytes += length;
        final Long limit = this.undertowExchange.getAttachment(REQUEST_BODY_LIMIT);
        if (limit != null && this.relayedRequestBytes > limit)
        {
            throw new RequestBodyTooLargeException();
        }
    }

    @Override
    public OutputStream commit(final boolean body)
    {
        // The commit listener runs when Undertow commits the head, on this thread.
        if (!body)
        {
            this.undertowExchange.endExchange();
            return null;
        }
        return ParkingChannelStreams.responseBody(this.undertowExchange);
    }

    @Override
    public void abortResponse()
    {
        IoUtils.safeClose(this.undertowExchange.getConnection());
    }

    @Override
    public void attemptedTarget(final URI target)
    {
        this.attemptedTarget = target.toString();
    }

    /**
     * A body without Content-Length is sent to the upstream chunked, and if reading it fails
     * part-way the proxy closes the upstream request off with a terminating chunk, handing the
     * upstream a truncated body as a complete one (see {@link UpstreamAbort}). Such bodies get a
     * guard that aborts the upstream first, and that also enforces any RequestSizeLimit - which
     * a body of undeclared length could otherwise only be held to after the fact. A body with a
     * Content-Length needs neither: the upstream request is fixed-length, so a short body fails
     * rather than completes, and an oversized declared length was refused up front.
     */
    private static void guardChunkedRequestBody(final HttpServerExchange exchange)
    {
        if (exchange.isRequestComplete() || exchange.getRequestHeaders().contains(Headers.CONTENT_LENGTH))
        {
            return;
        }
        final Long limit = exchange.getAttachment(REQUEST_BODY_LIMIT);
        final long maxBytes = limit != null ? limit : Long.MAX_VALUE;
        exchange.addRequestWrapper((factory, ex) -> new RequestBodyGuardConduit(factory.create(), ex, maxBytes));
    }

    @Override
    protected String[] attemptedUpstreams()
    {
        if (this.attemptedTarget != null)
        {
            return new String[]{this.attemptedTarget};
        }
        return DiagnosticProxyClient.getAttemptedUris(this.undertowExchange);
    }

    @Override
    public void onConnectionClose(final Runnable listener)
    {
        this.undertowExchange.getConnection().addCloseListener(connection -> listener.run());
    }

    @Override
    public TrafficMetrics trafficMetrics()
    {
        return this.undertowExchange.getAttachment(TrafficMetricsHandler.SIZE_METRICS_KEY);
    }

    @Override
    protected void listenForCommit()
    {
        this.undertowExchange.addResponseCommitListener(COMMIT_LISTENER);
    }

    @Override
    protected void listenForCompletion()
    {
        this.undertowExchange.addExchangeCompleteListener(COMPLETION_LISTENER);
    }

    // --- Filter-facing -----------------------------------------------------------------------

    @Override
    public void limitRequestBody(final long maxBytes)
    {
        // Recorded here and enforced by RequestBodyGuardConduit where the proxy reads the body:
        // setMaxEntitySize cannot do it, as HTTP/1.1 fixes the limit at parse time. Only ever
        // tightens; Undertow still enforces max_entity_size.
        final Long current = this.undertowExchange.getAttachment(REQUEST_BODY_LIMIT);
        if (current == null || maxBytes < current)
        {
            this.undertowExchange.putAttachment(REQUEST_BODY_LIMIT, maxBytes);
        }
    }

    @Override
    public <T> void setAttachment(final StateKey<T> key, final T value)
    {
        final AttachmentKey<T> undertowKey = getOrCreateUndertowKey(key);
        this.undertowExchange.putAttachment(undertowKey, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getAttachment(final StateKey<T> key)
    {
        final int id = key.id();
        final AttachmentKey<?>[] registry = KEY_REGISTRY;

        // Lock-free, zero-allocation array lookup
        if (id >= registry.length || registry[id] == null)
        {
            return null;
        }

        return (T) this.undertowExchange.getAttachment(registry[id]);
    }

    @SuppressWarnings("unchecked")
    private <T> AttachmentKey<T> getOrCreateUndertowKey(final StateKey<T> key)
    {
        final int id = key.id();
        AttachmentKey<?>[] registry = KEY_REGISTRY;

        // Fast path: Key already mapped
        if (id < registry.length)
        {
            final AttachmentKey<?> existing = registry[id];
            if (existing != null)
            {
                return (AttachmentKey<T>) existing;
            }
        }

        // Slow path: Map the key. This only happens once per unique StateKey during application lifecycle.
        synchronized (REGISTRY_LOCK)
        {
            registry = KEY_REGISTRY; // Re-read volatile inside lock

            // Expand array if the ID exceeds current bounds
            if (id >= registry.length)
            {
                final AttachmentKey<?>[] newRegistry = new AttachmentKey<?>[Math.max(registry.length * 2, id + 1)];
                System.arraycopy(registry, 0, newRegistry, 0, registry.length);
                registry = newRegistry;
                KEY_REGISTRY = registry; // Volatile write publishes the new array
            }

            AttachmentKey<?> undertowKey = registry[id];
            if (undertowKey == null)
            {
                undertowKey = AttachmentKey.create(Object.class);
                registry[id] = undertowKey;
            }
            return (AttachmentKey<T>) undertowKey;
        }
    }
}
