package com.ethlo.r7.server.blocking;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
import com.ethlo.r7.server.RequestPaths;
import com.ethlo.r7.server.ServerExchange;
import com.ethlo.r7.server.StaticFiles;
import com.ethlo.r7.server.UpstreamHandle;
import com.ethlo.r7.status.TrafficMetrics;
import com.ethlo.r7.upstream.HttpUpstream;
import com.ethlo.r7.upstream.ProxiedExchange;
import com.ethlo.r7.upstream.ProxyFailure;
import com.ethlo.r7.upstream.RequestBodyTooLargeException;
import com.ethlo.r7.upstream.Tunnel;
import com.ethlo.r7.upstream.UpstreamRelay;
import com.ethlo.r7.util.ImmutableGatewayRequest;
import com.ethlo.r7.util.ImmutableGatewayResponse;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * One request on a server that gives each request a thread of its own - a virtual thread on
 * Helidon Níma, a container thread (virtual or not) in a servlet container - as the pipeline sees
 * it, proxied through r7-upstream's {@link UpstreamRelay}, which it serves as the client side.
 * <p>
 * Everything here runs on the request's thread, synchronously: the pipeline's {@code handle}
 * returns when the response has been sent, so there is no I/O thread to protect
 * ({@link #mayBlock()} is always true), and the commit and completion "listeners" are flags
 * this class acts on at the right moment - commit just before the response head is written,
 * completion once {@link GatewayPipeline#handle} has returned ({@link BlockingGateway#handle}).
 * <p>
 * A server supplies the parsed request to the constructor and implements the few methods that
 * touch its own request and response objects; error answers, byte counts and the journal tees
 * live here, once, and the upstream exchange itself in the relay.
 * <p>
 * A WebSocket handshake the upstream accepts becomes a {@link Tunnel}: {@link #upgrade} commits
 * the 101 through {@link #switchProtocols}, and once {@link BlockingGateway#handle} has returned
 * the server passes the client connection to {@link #runTunnel} - or, if it never gets it, calls
 * {@link #abandonTunnel}. Either one ends the exchange for the journal and the metrics, which
 * wait for the connection to close.
 */
public abstract class BlockingServerExchange extends ServerExchange implements TrafficMetrics, ProxiedExchange
{
    private static final String HTTP_1_1 = "HTTP/1.1";

    private final String protocol;
    private final String method;
    private final String rawPath;
    private final String decodedPath;
    private final String rawQuery;
    private final MutableGatewayHeaders liveHeaders;
    private final long startNanos = System.nanoTime();
    private final long requestHeaderBytes;
    private final int requestHeaderCount;

    private Object[] attachments;
    private boolean commitRequested;
    private boolean committed;
    private boolean completionRequested;
    private boolean headWritten;
    private boolean aborted;
    private Consumer<ByteBuffer> requestTee;
    private Consumer<ByteBuffer> responseTee;
    private long requestBodyLimit = Long.MAX_VALUE;
    private String attempted;
    private Tunnel tunnel;
    private Runnable[] closeListeners;
    private final AtomicBoolean connectionClosed = new AtomicBoolean();

    private long requestBodyBytes;
    private long responseHeaderBytes;
    private long responseBodyBytes;

    /**
     * @param method   the request method as sent
     * @param rawPath  the request path as sent: not decoded, and above all not normalised - the
     *                 path guard must see the dot segments and encoded slashes a server's own
     *                 path API may already have resolved
     * @param rawQuery the query as sent, without the '?'; null or empty for none
     * @param headers  the request headers, copied off the wire. The pipeline sanitises this copy
     *                 and filters change it; it is what is forwarded
     */
    protected BlockingServerExchange(final GatewayPipeline pipeline, final String method, final String rawPath, final String rawQuery, final WireHeaders headers)
    {
        this(pipeline, HTTP_1_1, method, rawPath, rawQuery, headers);
    }

    /**
     * @param protocol the protocol the client spoke, as a start line names it ("HTTP/1.1",
     *                 "HTTP/2.0"): journaled, and it decides whether forwarded headers need
     *                 checking for what only HTTP/2 can carry (see {@link #parsedAsHttp1()})
     */
    protected BlockingServerExchange(final GatewayPipeline pipeline, final String protocol, final String method, final String rawPath, final String rawQuery, final WireHeaders headers)
    {
        super(pipeline);
        this.protocol = protocol;
        this.method = method;
        this.rawPath = RequestPaths.originForm(rawPath);
        this.decodedPath = RequestPaths.decode(this.rawPath);
        this.rawQuery = rawQuery == null ? "" : rawQuery;
        this.liveHeaders = headers;
        // [0]: bytes of the head as sent, [1]: header lines
        final long[] size = {method.length() + 1 + rawPath.length() + (this.rawQuery.isEmpty() ? 0 : 1 + this.rawQuery.length()) + 1 + protocol.length() + 2, 0};
        headers.forEach(size, (sz, name, value) ->
        {
            sz[0] += name.length() + 2 + value.length() + 2;
            sz[1]++;
        });
        this.requestHeaderBytes = size[0] + 2;
        this.requestHeaderCount = (int) size[1];
    }

    // --- What the server provides ------------------------------------------------------------

    /**
     * The request body as the server de-framed it; read once, only when the request has one.
     */
    protected abstract InputStream requestBody() throws IOException;

    /**
     * Sets the response status and adds every header, keeping repeated names as separate lines.
     * Called once, just before the body is written.
     */
    protected abstract void writeHead(int status, GatewayHeaders headers);

    /**
     * Sends the whole body after {@link #writeHead}, framed with its length, and ends the response.
     */
    protected abstract void send(byte[] body) throws IOException;

    /**
     * Ends a response that has no body (HEAD, 204, 304) after {@link #writeHead}.
     */
    protected abstract void sendNoBody() throws IOException;

    /**
     * The stream to write a relayed body to after {@link #writeHead}; closing it ends the response.
     */
    protected abstract OutputStream responseBody() throws IOException;

    /**
     * Whether the response has already started, in which case an upstream failure can no longer
     * become an error response.
     */
    protected abstract boolean isResponseStarted();

    /**
     * Whether this server can hand over the client connection after a 101; false by default,
     * and the relay then answers 502 rather than commit a switch nothing can carry.
     */
    protected boolean canSwitchProtocols()
    {
        return false;
    }

    /**
     * Sends the 101 head, its headers already on the response, and arranges for the client
     * connection to reach {@link #runTunnel} once the handler has returned. Only called when
     * {@link #canSwitchProtocols()} is true.
     */
    protected void switchProtocols(final GatewayHeaders headers) throws IOException
    {
        throw new UnsupportedOperationException();
    }

    /**
     * The scheme the client used, for X-Forwarded-Proto when no trusted proxy set one.
     */
    protected String scheme()
    {
        return "http";
    }

    /**
     * Runs the completion work the pipeline registered, after {@link GatewayPipeline#handle}.
     */
    void complete()
    {
        if (this.completionRequested)
        {
            this.completionRequested = false;
            pipeline().completeJournal(this);
            pipeline().runCompletedFilters(this);
        }
    }

    // --- The request as the server parsed it ----------------------------------------------------

    @Override
    protected String method()
    {
        return this.method;
    }

    @Override
    protected String decodedPath()
    {
        return this.decodedPath;
    }

    @Override
    protected long requestStartNanos()
    {
        return this.startNanos;
    }

    @Override
    protected MutableGatewayHeaders requestHeaders()
    {
        return this.liveHeaders;
    }

    @Override
    protected MutableGatewayRequest openLiveRequest()
    {
        final RemoteAddressResolver.RemoteInfo remote = remoteInfo();
        return new BlockingGatewayRequest(this.protocol, method(), decodedPath(), this.rawPath, this.rawQuery, this.liveHeaders, remote.address(), remote.source());
    }

    @Override
    protected GatewayRequest snapshotClientRequest()
    {
        final RemoteAddressResolver.RemoteInfo remote = remoteInfo();
        final MutableGatewayHeaders copy = copyOf(this.liveHeaders);
        return new ImmutableGatewayRequest(this.protocol, copy, decodedPath(), this.rawPath, method(),
                new BlockingGatewayRequest.Query(this.rawQuery), new BlockingGatewayRequest.HeaderCookies(copy),
                remote.address(), remote.source());
    }

    @Override
    protected MutableGatewayResponse openClientResponse()
    {
        return new BlockingGatewayResponse();
    }

    @Override
    protected GatewayResponse snapshotResponse()
    {
        return new ImmutableGatewayResponse(this.protocol, copyOf(clientResponse().headers()), clientResponse().status(), true);
    }

    private static MutableGatewayHeaders copyOf(final GatewayHeaders headers)
    {
        return WireHeaders.copyOf(headers);
    }

    // --- Threading ---------------------------------------------------------------------------

    @Override
    protected final boolean mayBlock()
    {
        // Every request has its own thread: a blocking filter just blocks it (parks, if virtual).
        return true;
    }

    @Override
    protected final void resumeOnBlockingThread()
    {
        // Never called while mayBlock() holds; were it, running here is what it asks for.
        run();
    }

    // --- Answering ---------------------------------------------------------------------------

    @Override
    protected void sendBody(final ByteBuffer body)
    {
        commit();
        writeHead();
        final byte[] bytes = new byte[body.remaining()];
        body.duplicate().get(bytes);
        if (this.responseTee != null && bytes.length > 0)
        {
            this.responseTee.accept(ByteBuffer.wrap(bytes));
        }
        this.responseBodyBytes += bytes.length;
        try
        {
            send(bytes);
        }
        catch (final IOException e)
        {
            // The client went away; there is no one left to answer.
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override
    protected void serveStatic(final StaticContentFactory.StaticServeRequest request)
    {
        try
        {
            StaticFiles.serve(new StaticTarget(), request);
        }
        catch (final IOException e)
        {
            if (this.headWritten)
            {
                // Part of a file went out: the client must not take it for the whole.
                this.aborted = true;
                return;
            }
            clientResponse().status(500);
            clientResponse().headers().set("Content-Type", "text/plain; charset=utf-8");
            sendBody(ByteBuffer.wrap("Error serving static content".getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * This exchange as {@link StaticFiles} needs it.
     */
    private final class StaticTarget implements StaticFiles.Target
    {
        @Override
        public String method()
        {
            return upstreamRequest().method();
        }

        @Override
        public String path()
        {
            return upstreamRequest().path();
        }

        @Override
        public String clientTarget()
        {
            return rawQuery.isEmpty() ? rawPath : rawPath + '?' + rawQuery;
        }

        @Override
        public GatewayHeaders requestHeaders()
        {
            return liveHeaders;
        }

        @Override
        public void header(final String name, final String value)
        {
            clientResponse().headers().set(name, value);
        }

        @Override
        public void answer(final int status, final byte[] body)
        {
            clientResponse().status(status);
            if (body.length > 0)
            {
                sendBody(ByteBuffer.wrap(body));
                return;
            }
            commit();
            writeHead();
            try
            {
                sendNoBody();
            }
            catch (final IOException e)
            {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public void answerFile(final int status, final Path file, final long offset, final long length, final boolean headOnly) throws IOException
        {
            clientResponse().status(status);
            clientResponse().headers().set("Content-Length", Long.toString(length));
            commit();
            writeHead();
            if (headOnly)
            {
                sendNoBody();
                return;
            }
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ); OutputStream out = responseBody())
            {
                channel.position(offset);
                final InputStream in = Channels.newInputStream(channel);
                final byte[] buffer = new byte[64 * 1024];
                long remaining = length;
                while (remaining > 0)
                {
                    final int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (n == -1)
                    {
                        throw new IOException("File " + file + " shrank while it was being served");
                    }
                    onResponseBody(buffer, 0, n);
                    out.write(buffer, 0, n);
                    remaining -= n;
                }
            }
        }
    }

    @Override
    protected void teeRequestBody(final Consumer<ByteBuffer> sink)
    {
        this.requestTee = sink;
    }

    @Override
    protected void teeResponseBody(final Consumer<ByteBuffer> sink)
    {
        this.responseTee = sink;
    }

    @Override
    protected String[] attemptedUpstreams()
    {
        return this.attempted == null ? new String[0] : new String[]{this.attempted};
    }

    @Override
    public void onConnectionClose(final Runnable listener)
    {
        // Registered at commit, on the request's thread, for an upgraded WebSocket only: by the
        // pipeline for the journal's end of the exchange, and by the metrics filter.
        if (this.closeListeners == null)
        {
            this.closeListeners = new Runnable[]{listener};
        }
        else
        {
            this.closeListeners = java.util.Arrays.copyOf(this.closeListeners, this.closeListeners.length + 1);
            this.closeListeners[this.closeListeners.length - 1] = listener;
        }
    }

    /**
     * Whether the handler switched the client connection to a tunnel, which the server must now
     * {@link #runTunnel run} or {@link #abandonTunnel abandon}.
     */
    public final boolean switchedProtocols()
    {
        return this.tunnel != null;
    }

    /**
     * Runs the tunnel over the client connection until either side closes, then ends the
     * exchange. The bytes tunnelled each way count as the exchange's body bytes, as the journal's
     * end of the exchange reports them. Call after {@link BlockingGateway#handle} has returned:
     * the journal's entries for the handshake are written there, and its end must follow them.
     */
    public final void runTunnel(final Tunnel.Client client)
    {
        try
        {
            this.tunnel.run(new CountingClient(client));
        }
        finally
        {
            connectionClosed();
        }
    }

    /**
     * For a server that will not get the client connection after all (it failed, or is stopping):
     * releases the upstream connection and ends the exchange. Safe to call after
     * {@link #runTunnel}, from any thread; only the first of the two ends the exchange.
     */
    public final void abandonTunnel()
    {
        try
        {
            this.tunnel.close();
        }
        finally
        {
            connectionClosed();
        }
    }

    /**
     * The tunnel, for a server that has to close it from outside or report its idle time.
     */
    protected final Tunnel tunnel()
    {
        return this.tunnel;
    }

    private void connectionClosed()
    {
        if (this.connectionClosed.compareAndSet(false, true) && this.closeListeners != null)
        {
            for (final Runnable listener : this.closeListeners)
            {
                listener.run();
            }
        }
    }

    /**
     * Counts what goes through the tunnel. Each counter is written by one direction's thread only,
     * and read once {@link Tunnel#run} has joined both.
     */
    private final class CountingClient implements Tunnel.Client
    {
        private final Tunnel.Client client;

        CountingClient(final Tunnel.Client client)
        {
            this.client = client;
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException
        {
            final int n = this.client.read(buffer, offset, length);
            if (n > 0)
            {
                requestBodyBytes += n;
            }
            return n;
        }

        @Override
        public void write(final byte[] buffer, final int offset, final int length) throws IOException
        {
            this.client.write(buffer, offset, length);
            responseBodyBytes += length;
        }

        @Override
        public void close()
        {
            this.client.close();
        }
    }

    @Override
    public TrafficMetrics trafficMetrics()
    {
        return this;
    }

    @Override
    protected void listenForCommit()
    {
        this.commitRequested = true;
    }

    @Override
    protected void listenForCompletion()
    {
        this.completionRequested = true;
    }

    /**
     * The pipeline's commit work: once, just before the response head goes out.
     */
    private void commit()
    {
        if (this.commitRequested && !this.committed)
        {
            this.committed = true;
            pipeline().onResponseCommit(this);
        }
        this.responseHeaderBytes = headBytes(clientResponse());
    }

    private void writeHead()
    {
        final MutableGatewayResponse response = clientResponse();
        this.headWritten = true;
        writeHead(response.status(), response.headers());
    }

    private long headBytes(final MutableGatewayResponse response)
    {
        final long[] size = {this.protocol.length() + 1 + 3 + 2};
        response.headers().forEach(size, (s, name, value) -> s[0] += name.length() + 2 + value.length() + 2);
        return size[0] + 2;
    }

    // --- Proxying ----------------------------------------------------------------------------

    @Override
    protected void proxy(final UpstreamHandle handle) throws Exception
    {
        try
        {
            UpstreamRelay.relay((HttpUpstream) handle, this, route().id());
        }
        catch (final ProxyFailure failure)
        {
            respondError(failure.status(), failure.message());
            if (failure.getCause() != null)
            {
                throw failure.getCause();
            }
        }
    }

    @Override
    public boolean parsedAsHttp1()
    {
        return this.protocol.startsWith("HTTP/1.");
    }

    @Override
    public String forwardMethod()
    {
        return upstreamRequest().method();
    }

    @Override
    public String forwardTarget()
    {
        return ((BlockingGatewayRequest) upstreamRequest()).target();
    }

    @Override
    public GatewayHeaders forwardHeaders()
    {
        return this.liveHeaders;
    }

    @Override
    public String forwardedFor()
    {
        final InetSocketAddress peer = peerAddress();
        return upstreamRequest().remoteAddress() != null && peer != null ? peer.getAddress().getHostAddress() : "unknown";
    }

    @Override
    public String forwardedProto()
    {
        return scheme();
    }

    @Override
    public InputStream openRequestBody() throws IOException
    {
        return requestBody();
    }

    @Override
    public void onRequestBody(final byte[] buffer, final int offset, final int length) throws IOException
    {
        this.requestBodyBytes += length;
        if (this.requestBodyBytes > this.requestBodyLimit)
        {
            throw new RequestBodyTooLargeException();
        }
        if (this.requestTee != null)
        {
            this.requestTee.accept(ByteBuffer.wrap(buffer, offset, length).asReadOnlyBuffer());
        }
    }

    @Override
    public OutputStream commit(final boolean body) throws IOException
    {
        commit();
        writeHead();
        if (!body)
        {
            sendNoBody();
            return null;
        }
        return responseBody();
    }

    @Override
    public void onResponseBody(final byte[] buffer, final int offset, final int length)
    {
        this.responseBodyBytes += length;
        if (this.responseTee != null)
        {
            this.responseTee.accept(ByteBuffer.wrap(buffer, offset, length).asReadOnlyBuffer());
        }
    }

    @Override
    public boolean canUpgrade()
    {
        // Over HTTP/2 a WebSocket is bootstrapped differently (RFC 8441), and Upgrade is not
        // even allowed; a 101 for such a request has no connection to switch.
        return parsedAsHttp1() && canSwitchProtocols() && !this.headWritten && !isResponseStarted();
    }

    @Override
    public void upgrade(final Tunnel tunnel) throws IOException
    {
        // The pipeline's commit work first: it sees the 101, marks the exchange an upgraded
        // WebSocket and registers what must run when the connection closes.
        commit();
        this.headWritten = true;
        this.tunnel = tunnel;
        try
        {
            switchProtocols(clientResponse().headers());
        }
        catch (final IOException | RuntimeException e)
        {
            // Some of the 101 may be out, or the status set where the server will send it; no
            // error answer can follow, so the connection is dropped.
            this.aborted = true;
            throw e;
        }
    }

    @Override
    public void attemptedTarget(final URI target)
    {
        this.attempted = target.toString();
    }

    @Override
    public void abortResponse()
    {
        this.aborted = true;
    }

    /**
     * Whether the response was abandoned part-way; the server must then drop the connection
     * rather than complete the message ({@link BlockingGateway#handle} throws to make it).
     */
    boolean isAborted()
    {
        return this.aborted;
    }

    int requestHeaderCount()
    {
        return this.requestHeaderCount;
    }

    /**
     * The status sent, or to be sent; 200 until something set another.
     */
    int responseStatus()
    {
        return clientResponse().status();
    }

    /**
     * Answers without running the pipeline, for a request the server should not have accepted,
     * and closes the connection after it, as a server's own parser does when it refuses a head.
     */
    void refuse(final int status, final String message)
    {
        closeConnectionAfterResponse();
        respondError(status, message);
    }

    private void respondError(final int status, final String message)
    {
        // Our own flag first: a server's "response sent" can stay false while a body streams.
        if (this.headWritten || isResponseStarted())
        {
            return;
        }
        clientResponse().status(status);
        clientResponse().headers().set("Content-Type", "text/plain; charset=utf-8");
        clientResponse().headers().set("X-Content-Type-Options", "nosniff");
        sendBody(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
    }

    // --- Filter-facing -----------------------------------------------------------------------

    @Override
    public void limitRequestBody(final long maxBytes)
    {
        // Only ever tightens.
        this.requestBodyLimit = Math.min(this.requestBodyLimit, maxBytes);
    }

    @Override
    public <T> void setAttachment(final StateKey<T> key, final T value)
    {
        final int id = key.id();
        if (this.attachments == null)
        {
            this.attachments = new Object[Math.max(16, id + 1)];
        }
        else if (id >= this.attachments.length)
        {
            this.attachments = java.util.Arrays.copyOf(this.attachments, Math.max(this.attachments.length * 2, id + 1));
        }
        this.attachments[id] = value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getAttachment(final StateKey<T> key)
    {
        final int id = key.id();
        return this.attachments == null || id >= this.attachments.length ? null : (T) this.attachments[id];
    }

    // --- TrafficMetrics ----------------------------------------------------------------------

    @Override
    public long requestHeaderBytes()
    {
        return this.requestHeaderBytes;
    }

    @Override
    public long requestBodyBytes()
    {
        return this.requestBodyBytes;
    }

    @Override
    public long responseHeaderBytes()
    {
        return this.responseHeaderBytes;
    }

    @Override
    public long responseBodyBytes()
    {
        return this.responseBodyBytes;
    }

    @Override
    public long totalRequestBytes()
    {
        return this.requestHeaderBytes + this.requestBodyBytes;
    }

    @Override
    public long totalResponseBytes()
    {
        return this.responseHeaderBytes + this.responseBodyBytes;
    }
}
