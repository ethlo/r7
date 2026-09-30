package com.ethlo.r7.helidon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.GatewayResponse;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.MutableGatewayRequest;
import com.ethlo.r7.api.MutableGatewayResponse;
import com.ethlo.r7.api.StateKey;
import com.ethlo.r7.core.proxy.NoAvailableTargetException;
import com.ethlo.r7.core.proxy.ProxyConnectionException;
import com.ethlo.r7.filters.StaticContentFactory;
import com.ethlo.r7.server.GatewayPipeline;
import com.ethlo.r7.server.RemoteAddressResolver;
import com.ethlo.r7.server.RequestPaths;
import com.ethlo.r7.server.ServerExchange;
import com.ethlo.r7.server.UpstreamHandle;
import com.ethlo.r7.status.TrafficMetrics;
import com.ethlo.r7.util.ImmutableGatewayRequest;
import com.ethlo.r7.util.ImmutableGatewayResponse;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import io.helidon.http.Header;
import io.helidon.http.HeaderNames;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

/**
 * One request on Helidon's Níma web server, as the pipeline sees it.
 * <p>
 * Everything here runs on the request's own virtual thread, synchronously: the pipeline's
 * {@code handle} returns when the response has been sent, so there is no I/O thread to protect
 * ({@link #isOnIoThread()} is always false), and the commit and completion "listeners" are flags
 * this class acts on at the right moment - commit just before the response head is written,
 * completion once {@link GatewayPipeline#handle} has returned.
 */
final class HelidonGatewayExchange extends ServerExchange implements TrafficMetrics
{
    private static final String PROTOCOL = "HTTP/1.1";
    private static final byte[] CRLF = {'\r', '\n'};

    private final ServerRequest req;
    private final ServerResponse res;
    private final String rawPath;
    private final String decodedPath;
    private final String rawQuery;
    private final MutableGatewayHeaders liveHeaders;
    private final long startNanos = System.nanoTime();
    private final long requestHeaderBytes;

    private Object[] attachments;
    private boolean commitRequested;
    private boolean committed;
    private boolean completionRequested;
    private Consumer<ByteBuffer> requestTee;
    private Consumer<ByteBuffer> responseTee;
    private long requestBodyLimit = Long.MAX_VALUE;
    private String attempted;

    private long requestBodyBytes;
    private long responseHeaderBytes;
    private long responseBodyBytes;

    HelidonGatewayExchange(final GatewayPipeline pipeline, final ServerRequest req, final ServerResponse res)
    {
        super(pipeline);
        this.req = req;
        this.res = res;
        this.rawPath = req.prologue().uriPath().rawPath();
        // Not UriPath.path(): Helidon resolves dot segments there, and the guard must see them.
        this.decodedPath = RequestPaths.decode(this.rawPath);
        this.rawQuery = req.prologue().query().rawValue();
        // Helidon's request headers are immutable; the pipeline sanitises and filters change a
        // copy, and that copy is what is forwarded.
        final WireHeaders headers = new WireHeaders();
        long headerBytes = method().length() + 1 + rawPath.length() + (rawQuery.isEmpty() ? 0 : 1 + rawQuery.length()) + 1 + PROTOCOL.length() + 2;
        for (final Header header : req.headers())
        {
            final String name = header.name();
            for (final String value : header.allValues())
            {
                headers.addFromWire(name, value);
                headerBytes += name.length() + 2 + value.length() + 2;
            }
        }
        this.liveHeaders = headers;
        this.requestHeaderBytes = headerBytes + 2;
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

    // --- The request as Helidon parsed it ----------------------------------------------------

    @Override
    protected String method()
    {
        return this.req.prologue().method().text();
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
    protected InetSocketAddress peerAddress()
    {
        final SocketAddress address = this.req.remotePeer().address();
        return address instanceof InetSocketAddress inet ? inet : null;
    }

    @Override
    protected MutableGatewayRequest openLiveRequest()
    {
        final RemoteAddressResolver.RemoteInfo remote = remoteInfo();
        return new HelidonGatewayRequest(PROTOCOL, method(), decodedPath(), this.rawPath, this.rawQuery, this.liveHeaders, remote.address(), remote.source());
    }

    @Override
    protected GatewayRequest snapshotClientRequest()
    {
        final RemoteAddressResolver.RemoteInfo remote = remoteInfo();
        final MutableGatewayHeaders copy = copyOf(this.liveHeaders);
        return new ImmutableGatewayRequest(PROTOCOL, copy, decodedPath(), this.rawPath, method(),
                new HelidonGatewayRequest.Query(this.rawQuery), new HelidonGatewayRequest.HeaderCookies(copy),
                remote.address(), remote.source());
    }

    @Override
    protected MutableGatewayResponse openClientResponse()
    {
        return new HelidonGatewayResponse();
    }

    @Override
    protected GatewayResponse snapshotResponse()
    {
        return new ImmutableGatewayResponse(PROTOCOL, copyOf(clientResponse().headers()), clientResponse().status(), true);
    }

    private static MutableGatewayHeaders copyOf(final GatewayHeaders headers)
    {
        return WireHeaders.copyOf(headers);
    }

    // --- Threading ---------------------------------------------------------------------------

    @Override
    protected boolean isOnIoThread()
    {
        // Every Níma request runs on its own virtual thread: a blocking filter just parks it.
        return false;
    }

    @Override
    protected void dispatch()
    {
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
        this.res.send(bytes);
    }

    @Override
    protected void serveStatic(final StaticContentFactory.StaticServeRequest request)
    {
        clientResponse().status(501);
        clientResponse().headers().set("Content-Type", "text/plain; charset=utf-8");
        sendBody(ByteBuffer.wrap("Static content is not supported by r7-helidon (experimental)".getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    protected void closeConnectionAfterResponse()
    {
        this.res.header("Connection", "close");
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
        // Only called for an upgraded websocket, which r7-helidon does not proxy yet.
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
     * What the Undertow commit listener does: once, just before the response head goes out.
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
        this.res.status(response.status());
        // Added, not set: a name that repeats (Set-Cookie above all) must keep every line.
        response.headers().forEach(this.res.headers(), (h, name, value) -> h.add(HeaderNames.create(name), value));
    }

    private static long headBytes(final MutableGatewayResponse response)
    {
        final long[] size = {PROTOCOL.length() + 1 + 3 + 2};
        response.headers().forEach(size, (s, name, value) -> s[0] += name.length() + 2 + value.length() + 2);
        return size[0] + 2;
    }

    // --- Proxying ----------------------------------------------------------------------------

    @Override
    protected void proxy(final UpstreamHandle handle) throws Exception
    {
        final HttpUpstream upstream = (HttpUpstream) handle;
        final HttpUpstream.Target target = upstream.pick();
        if (target == null)
        {
            respondError(503, "No upstream available");
            throw new NoAvailableTargetException("No target is up for " + route().id());
        }
        this.attempted = target.uri.toString();

        final HelidonGatewayRequest live = (HelidonGatewayRequest) upstreamRequest();
        final byte[] head = requestHead(target, live);
        final Framing framing = Framing.ofRequest(this.liveHeaders);

        HttpUpstream.Connection connection = null;
        try
        {
            connection = target.acquire();
            String[] statusAndHeaders;
            try
            {
                statusAndHeaders = exchangeHead(connection, head, framing);
            }
            catch (final IOException e)
            {
                // A pooled connection the upstream closed while it sat idle fails on first use.
                // Reconnect once, and only when there was no body: a body may be half-sent.
                if (!connection.reused || framing != Framing.NONE)
                {
                    throw e;
                }
                connection.close();
                connection = target.acquire();
                statusAndHeaders = exchangeHead(connection, head, framing);
            }
            final boolean reusable = relayResponse(connection, statusAndHeaders);
            if (reusable)
            {
                target.release(connection);
            }
            else
            {
                connection.close();
            }
            connection = null;
        }
        catch (final ConnectException e)
        {
            respondError(503, "Upstream connection failed");
            throw new ProxyConnectionException("TCP Connection failed to: " + target.uri);
        }
        catch (final SocketTimeoutException e)
        {
            respondError(504, "Upstream timed out");
            throw e;
        }
        catch (final RequestBodyTooLargeException e)
        {
            respondError(413, "Request body too large");
        }
        catch (final IOException e)
        {
            respondError(502, "Upstream failed");
            throw new ProxyConnectionException("Upstream exchange failed with " + target.uri + ": " + e.getMessage());
        }
        finally
        {
            if (connection != null)
            {
                connection.close();
            }
        }
    }

    private enum Framing
    {
        NONE, LENGTH, CHUNKED;

        static Framing ofRequest(final GatewayHeaders headers)
        {
            if (headers.getFirst("Transfer-Encoding") != null)
            {
                return CHUNKED;
            }
            return headers.getFirst("Content-Length") != null ? LENGTH : NONE;
        }
    }

    /**
     * The request line and headers to send upstream: the live headers (already sanitised by the
     * pipeline) with Host rewritten to the target, the gateway's X-Forwarded-* added the way
     * Undertow's proxy adds them (extending a trusted proxy's chain, starting one otherwise), and
     * framing and Expect left to this hop.
     */
    private byte[] requestHead(final HttpUpstream.Target target, final HelidonGatewayRequest live)
    {
        final StringBuilder sb = new StringBuilder(512);
        sb.append(live.method()).append(' ').append(target.basePath).append(live.target()).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(target.hostHeader).append("\r\n");
        final String originalHost = this.liveHeaders.getFirst("Host");
        this.liveHeaders.forEach(sb, (b, name, value) ->
        {
            if (!name.equalsIgnoreCase("Host") && !name.equalsIgnoreCase("Expect") && !name.equalsIgnoreCase("X-Forwarded-For")
                    && !name.equalsIgnoreCase("Content-Length") && !name.equalsIgnoreCase("Transfer-Encoding"))
            {
                b.append(name).append(": ").append(value).append("\r\n");
            }
        });
        final String peer = live.remoteAddress() != null ? peerIp() : "unknown";
        final List<String> chain = new ArrayList<>();
        for (final String value : this.liveHeaders.getAll("X-Forwarded-For"))
        {
            chain.add(value);
        }
        chain.add(peer);
        sb.append("X-Forwarded-For: ").append(String.join(", ", chain)).append("\r\n");
        if (this.liveHeaders.getFirst("X-Forwarded-Host") == null && originalHost != null)
        {
            sb.append("X-Forwarded-Host: ").append(originalHost).append("\r\n");
        }
        if (this.liveHeaders.getFirst("X-Forwarded-Proto") == null)
        {
            sb.append("X-Forwarded-Proto: http\r\n");
        }
        final String contentLength = this.liveHeaders.getFirst("Content-Length");
        if (this.liveHeaders.getFirst("Transfer-Encoding") != null)
        {
            sb.append("Transfer-Encoding: chunked\r\n");
        }
        else if (contentLength != null)
        {
            sb.append("Content-Length: ").append(contentLength).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * The immediate peer's address, which is what the gateway vouches for in X-Forwarded-For
     * (the resolved client address may have come from a trusted proxy's chain).
     */
    private String peerIp()
    {
        final InetSocketAddress peer = peerAddress();
        return peer != null ? peer.getAddress().getHostAddress() : "unknown";
    }

    /**
     * Sends the request head and body and reads the upstream's response head, skipping any
     * interim 1xx responses. Returns the status line followed by the header lines.
     */
    private String[] exchangeHead(final HttpUpstream.Connection connection, final byte[] head, final Framing framing) throws IOException
    {
        final OutputStream out = connection.out;
        out.write(head);
        if (framing != Framing.NONE)
        {
            copyRequestBody(out, framing);
        }
        out.flush();

        while (true)
        {
            final String statusLine = connection.reader.readLine();
            if (statusLine == null)
            {
                throw new IOException("Upstream closed the connection before responding");
            }
            final List<String> lines = new ArrayList<>();
            lines.add(statusLine);
            String line;
            while ((line = connection.reader.readLine()) != null && !line.isEmpty())
            {
                lines.add(line);
            }
            final int status = statusOf(statusLine);
            if (status >= 100 && status < 200 && status != 101)
            {
                continue;
            }
            return lines.toArray(new String[0]);
        }
    }

    private void copyRequestBody(final OutputStream out, final Framing framing) throws IOException
    {
        final InputStream in = this.req.content().inputStream();
        final byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1)
        {
            this.requestBodyBytes += n;
            if (this.requestBodyBytes > this.requestBodyLimit)
            {
                throw new RequestBodyTooLargeException();
            }
            if (this.requestTee != null)
            {
                this.requestTee.accept(ByteBuffer.wrap(buffer, 0, n).asReadOnlyBuffer());
            }
            if (framing == Framing.CHUNKED)
            {
                out.write(Integer.toHexString(n).getBytes(StandardCharsets.ISO_8859_1));
                out.write(CRLF);
                out.write(buffer, 0, n);
                out.write(CRLF);
            }
            else
            {
                out.write(buffer, 0, n);
            }
        }
        if (framing == Framing.CHUNKED)
        {
            out.write('0');
            out.write(CRLF);
            out.write(CRLF);
        }
    }

    /**
     * Relays the upstream's response to the client. Returns whether the upstream connection can
     * be reused: the body was framed and read to its end, and the upstream did not ask to close.
     */
    private boolean relayResponse(final HttpUpstream.Connection connection, final String[] head) throws IOException
    {
        final int status = statusOf(head[0]);
        if (status == 101)
        {
            // The upgrade request went upstream sanitised like any other; tunnelling the upgraded
            // connection is what the spike does not do yet.
            connection.close();
            respondError(502, "WebSocket tunnelling is not supported by r7-helidon (experimental)");
            return false;
        }
        final MutableGatewayResponse response = clientResponse();
        response.status(status);

        String connectionHeader = null;
        String contentLength = null;
        boolean chunked = false;
        for (int i = 1; i < head.length; i++)
        {
            final String line = head[i];
            final int colon = line.indexOf(':');
            if (colon <= 0)
            {
                continue;
            }
            final String name = line.substring(0, colon).trim();
            final String value = line.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Connection"))
            {
                connectionHeader = value;
            }
            else if (name.equalsIgnoreCase("Transfer-Encoding"))
            {
                chunked = value.toLowerCase().contains("chunked");
            }
            else if (name.equalsIgnoreCase("Content-Length"))
            {
                contentLength = value;
            }
            else if (!isHopByHop(name))
            {
                response.headers().add(name, value);
            }
        }

        final boolean noBody = "HEAD".equalsIgnoreCase(upstreamRequest().method()) || status == 204 || status == 304;
        if (!noBody && !chunked && contentLength != null)
        {
            response.headers().set("Content-Length", contentLength);
        }

        commit();
        writeHead();

        if (noBody)
        {
            this.res.send();
            return connectionHeader == null || !connectionHeader.equalsIgnoreCase("close");
        }

        final HttpUpstream.LineReader in = connection.reader;
        boolean framed = true;
        try (OutputStream out = this.res.outputStream())
        {
            if (chunked)
            {
                relayChunked(in, out);
            }
            else if (contentLength != null)
            {
                relayExactly(in, out, Long.parseLong(contentLength));
            }
            else
            {
                framed = false;
                relayExactly(in, out, Long.MAX_VALUE);
            }
        }
        return framed && (connectionHeader == null || !connectionHeader.equalsIgnoreCase("close"));
    }

    private void relayExactly(final HttpUpstream.LineReader in, final OutputStream out, final long length) throws IOException
    {
        final byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0)
        {
            final int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n == -1)
            {
                if (length == Long.MAX_VALUE)
                {
                    return;
                }
                throw new IOException("Upstream closed the connection " + remaining + " bytes short of its Content-Length");
            }
            writeResponseBody(out, buffer, n);
            remaining -= n;
        }
    }

    private void relayChunked(final HttpUpstream.LineReader in, final OutputStream out) throws IOException
    {
        final byte[] buffer = new byte[8192];
        while (true)
        {
            final String sizeLine = in.readLine();
            if (sizeLine == null)
            {
                throw new IOException("Upstream closed the connection inside a chunked body");
            }
            final int semicolon = sizeLine.indexOf(';');
            final long size = Long.parseLong((semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon)).trim(), 16);
            if (size == 0)
            {
                String trailer;
                while ((trailer = in.readLine()) != null && !trailer.isEmpty())
                {
                    // Trailers are not relayed in the spike.
                }
                return;
            }
            long remaining = size;
            while (remaining > 0)
            {
                final int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n == -1)
                {
                    throw new IOException("Upstream closed the connection inside a chunk");
                }
                writeResponseBody(out, buffer, n);
                remaining -= n;
            }
            in.readLine();
        }
    }

    private void writeResponseBody(final OutputStream out, final byte[] buffer, final int n) throws IOException
    {
        this.responseBodyBytes += n;
        if (this.responseTee != null)
        {
            this.responseTee.accept(ByteBuffer.wrap(buffer, 0, n).asReadOnlyBuffer());
        }
        out.write(buffer, 0, n);
    }

    private static boolean isHopByHop(final String name)
    {
        return name.equalsIgnoreCase("Keep-Alive") || name.equalsIgnoreCase("Proxy-Connection")
                || name.equalsIgnoreCase("TE") || name.equalsIgnoreCase("Trailer") || name.equalsIgnoreCase("Upgrade");
    }

    private static int statusOf(final String statusLine)
    {
        final int start = statusLine.indexOf(' ') + 1;
        return Integer.parseInt(statusLine.substring(start, start + 3));
    }

    private void respondError(final int status, final String message)
    {
        if (this.res.isSent())
        {
            return;
        }
        clientResponse().status(status);
        clientResponse().headers().set("Content-Type", "text/plain; charset=utf-8");
        clientResponse().headers().set("X-Content-Type-Options", "nosniff");
        sendBody(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class RequestBodyTooLargeException extends IOException
    {
        RequestBodyTooLargeException()
        {
            super("Request body exceeds the route's limit");
        }
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
