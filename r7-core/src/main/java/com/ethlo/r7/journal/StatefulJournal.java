package com.ethlo.r7.journal;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.zip.CRC32C;

import com.ethlo.r7.util.RedactUtil;
import com.ethlo.r7.api.CompletedGatewayExchange;
import com.ethlo.r7.api.GatewayAttributes;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.StatefulEntryConsumer;
import com.ethlo.r7.config.RouteJournalConfig;
import com.ethlo.r7.journal.api.Journal;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.util.FastGatewayHeaders;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

public final class StatefulJournal implements Journal
{
    private final Journal delegate;
    private final RouteJournalConfig config;
    private final CompletedGatewayExchange exchange;
    /**
     * Checksums of the body bytes this journal actually passed to the delegate, created on
     * the first fragment of that direction.
     * <p>
     * Lazy is the whole point. Eagerly created accumulators cannot distinguish "no body was
     * journaled" from "a body was journaled and it hashes to the CRC32C of nothing", and
     * they answer 0 for both. The reader has to know which, because verifying a checksum
     * against a body that was never stored reports a mismatch on a perfectly intact record.
     */
    private CRC32C requestChecksum;
    private CRC32C responseChecksum;

    private JournalLevel level;
    private String requestId;
    private boolean clientReqFlushed = false;
    private boolean upstreamReqFlushed = false;
    private boolean upstreamResFlushed = false;
    private boolean clientResFlushed = false;

    private ByteBuffer clientReqLine;
    private GatewayHeaders clientReqHeaders;
    private ByteBuffer upstreamReqLine;
    private GatewayHeaders upstreamReqHeaders;
    private ByteBuffer upstreamResLine;
    private GatewayHeaders upstreamResHeaders;
    private ByteBuffer clientResLine;
    private int clientStatusCode;
    private GatewayHeaders clientResHeaders;
    private int upstreamStatusCode;

    private long bytesWritten;
    private InetAddress remoteAddress;
    private IpSource remoteAddressSource;

    public StatefulJournal(final Journal delegate, final RouteJournalConfig config, final CompletedGatewayExchange exchange)
    {
        this.delegate = delegate;
        this.config = config;
        this.exchange = exchange;
    }

    @Override
    public int clientRequest(final JournalLevel level, final String reqId, final ByteBuffer startLine, final GatewayHeaders headers, final InetAddress remoteAddress, IpSource ipSource)
    {
        this.requestId = reqId;
        this.clientReqLine = cloneBuffer(startLine);
        this.clientReqHeaders = headers;
        this.remoteAddress = remoteAddress;
        this.remoteAddressSource = ipSource;

        if (config.request().level() == JournalLevel.FULL)
        {
            return checkAndFlushRequest();
        }
        return 0;
    }

    @Override
    public int upstreamRequest(final JournalLevel level, final String reqId, final ByteBuffer startLine, final GatewayHeaders headers)
    {
        this.upstreamReqLine = cloneBuffer(startLine);
        this.upstreamReqHeaders = headers;

        if (config.request().level() == JournalLevel.FULL)
        {
            return checkAndFlushRequest();
        }
        return 0;
    }

    @Override
    public int upstreamResponse(final JournalLevel level, final String reqId, final int upstreamStatusCode, final ByteBuffer startLine, final GatewayHeaders headers)
    {
        this.upstreamResLine = cloneBuffer(startLine);
        this.upstreamResHeaders = headers;
        this.upstreamStatusCode = upstreamStatusCode;

        int written = checkAndFlushRequest();
        written += checkAndFlushResponse();
        return written;
    }

    @Override
    public int clientResponse(final JournalLevel level, final String reqId, final int clientStatusCode, final ByteBuffer startLine, final GatewayHeaders headers)
    {
        this.clientStatusCode = clientStatusCode;
        this.clientResLine = cloneBuffer(startLine);
        this.clientResHeaders = headers;

        return checkAndFlushResponse();
    }

    @Override
    public int requestBody(final String reqId, final ByteBuffer data)
    {
        if (config.request().level() == JournalLevel.FULL)
        {
            // Anchor the body before writing it.
            //
            // Every other request-side decision resolves the level against the response
            // status, but the status is not known yet when body fragments arrive, so this
            // one cannot. When the two disagree — a base level of FULL that resolves to NONE
            // at the not-yet-known status — checkAndFlushRequest returns without emitting
            // anything, and the body entries land in the journal ahead of the ClientRequest
            // entry that anchors them.
            //
            // The reader then has nowhere to put those fragments, discards them as orphaned
            // bodies, and afterwards compares the recorded checksum against a body it threw
            // away: a mismatch reported on a record that is exactly what this writer
            // intended. If we are going to journal the body, the request goes first.
            final int anchored = flushRequestAtFull();

            if (requestChecksum == null)
            {
                requestChecksum = new CRC32C();
            }
            requestChecksum.update(data.duplicate());
            final int bodyBytes = delegate.requestBody(reqId, data);
            this.bytesWritten += bodyBytes;
            return anchored + bodyBytes;
        }
        return 0;
    }

    /**
     * Emits the request entries at FULL if they have not gone out yet.
     * <p>
     * Deliberately not status-resolved: this is called because a body is about to be
     * journaled, which already commits us to a FULL record. Recording the request at a lower
     * level, or not at all, would leave the body without the entry that gives it meaning.
     * The flushed flags make it a no-op once the request has been written.
     */
    private int flushRequestAtFull()
    {
        // Both handlers add their own output to bytesWritten, so this must not.
        return handleClientRequest(JournalLevel.FULL) + handleUpstreamRequest(JournalLevel.FULL);
    }

    @Override
    public int responseBody(final String reqId, final ByteBuffer data)
    {
        if (config.response().resolve(exchange.clientResponse().status()) == JournalLevel.FULL)
        {
            if (responseChecksum == null)
            {
                responseChecksum = new CRC32C();
            }
            responseChecksum.update(data.duplicate());
            final int written = delegate.responseBody(reqId, data);
            this.bytesWritten += written;
            return written;
        }
        return 0;
    }

    /**
     * The two checksum arguments are deliberately <em>not</em> forwarded.
     * <p>
     * This class decides what actually reaches the delegate — it gates every body fragment
     * on the effective journal level — so it is the only layer that can state a checksum
     * over the bytes that were really stored. A caller upstream sees the bytes on the wire,
     * which is a different set whenever the level is below FULL. Recording the caller's
     * value would produce a checksum of bytes the journal does not contain, and the reader
     * would report a mismatch on an intact record.
     */
    @Override
    public int endExchange(final String reqId, final GatewayAttributes attributes, final long requestStartTs, final long requestEndTs, final int statusCode, final long requestHeaderBytes, final long requestBodyBytes, final long responseHeaderBytes, final long responseBodyBytes, final long proxyStartTs, final long proxyFirstByteReceivedTs, final long proxyEndTs, final long ignoredRequestChecksum, final long ignoredResponseChecksum)
    {
        int written = checkAndFlushRequest();
        written += checkAndFlushResponse();

        final int endBytes = delegate.endExchange(reqId, attributes, requestStartTs, requestEndTs, statusCode, requestHeaderBytes, requestBodyBytes, responseHeaderBytes, responseBodyBytes, proxyStartTs, proxyFirstByteReceivedTs, proxyEndTs, JournalExchange.checksumOf(requestChecksum), JournalExchange.checksumOf(responseChecksum));
        this.bytesWritten += endBytes;
        return written + endBytes;
    }

    @Override
    public void close() throws java.io.IOException
    {
        delegate.close();
    }

    private int checkAndFlushRequest()
    {
        if (clientReqFlushed && upstreamReqFlushed)
        {
            return 0;
        }

        final int status = exchange.clientResponse().status();
        final JournalLevel reqLevel = config.request().resolve(status);
        final JournalLevel resLevel = config.response().resolve(status);

        JournalLevel effectiveLevel = reqLevel;
        if (effectiveLevel == JournalLevel.NONE && resLevel != JournalLevel.NONE)
        {
            effectiveLevel = JournalLevel.METADATA;
        }

        if (effectiveLevel == JournalLevel.NONE)
        {
            return 0;
        }

        int written = handleClientRequest(effectiveLevel);
        written += handleUpstreamRequest(effectiveLevel);
        return written;
    }

    private int handleUpstreamRequest(final JournalLevel effectiveLevel)
    {
        if (!upstreamReqFlushed && upstreamReqLine != null)
        {
            final GatewayHeaders headers = effectiveLevel == JournalLevel.METADATA ? FastGatewayHeaders.empty() : redactHeaders(upstreamReqHeaders, JournalSecurity.SAFE_REQUEST_HEADERS);
            final int written = delegate.upstreamRequest(effectiveLevel, requestId, upstreamReqLine, headers);
            this.bytesWritten += written;
            this.upstreamReqFlushed = true;
            this.upstreamReqLine = null;
            return written;
        }
        return 0;
    }

    private int handleClientRequest(final JournalLevel effectiveLevel)
    {
        if (!clientReqFlushed && clientReqLine != null)
        {
            final GatewayHeaders headers = effectiveLevel == JournalLevel.METADATA ? FastGatewayHeaders.empty() : redactHeaders(clientReqHeaders, JournalSecurity.SAFE_REQUEST_HEADERS);
            final int written = delegate.clientRequest(effectiveLevel, requestId, clientReqLine, headers, remoteAddress, remoteAddressSource);
            this.bytesWritten += written;
            this.clientReqFlushed = true;
            this.clientReqLine = null;
            return written;
        }
        return 0;
    }

    private int checkAndFlushResponse()
    {
        final int status = exchange.clientResponse().status();
        final JournalLevel resLevel = config.response().resolve(status);
        if (resLevel == JournalLevel.NONE)
        {
            return 0;
        }

        int written = handleUpstreamResponse(resLevel);
        written += handleClientResponse(resLevel);
        return written;
    }

    private int handleClientResponse(final JournalLevel resLevel)
    {
        if (!clientResFlushed && clientResLine != null)
        {
            final GatewayHeaders headers = resLevel == JournalLevel.METADATA ? FastGatewayHeaders.empty() : redactHeaders(clientResHeaders, JournalSecurity.SAFE_RESPONSE_HEADERS);
            final int written = delegate.clientResponse(resLevel, requestId, clientStatusCode, clientResLine, headers);
            this.bytesWritten += written;
            this.clientResFlushed = true;
            this.clientResLine = null;
            return written;
        }
        return 0;
    }

    private int handleUpstreamResponse(final JournalLevel resLevel)
    {
        if (!upstreamResFlushed && upstreamResLine != null)
        {
            final GatewayHeaders headers = resLevel == JournalLevel.METADATA ? FastGatewayHeaders.empty() : redactHeaders(upstreamResHeaders, JournalSecurity.SAFE_RESPONSE_HEADERS);
            final int written = delegate.upstreamResponse(resLevel, requestId, upstreamStatusCode, upstreamResLine, headers);
            this.bytesWritten += written;
            this.upstreamResFlushed = true;
            this.upstreamResLine = null;
            return written;
        }
        return 0;
    }

    private GatewayHeaders redactHeaders(final GatewayHeaders original, final Set<String> safeHeaders)
    {
        if (original == null)
        {
            return FastGatewayHeaders.empty();
        }
        final MutableGatewayHeaders redacted = new MutableFastGatewayHeaders();

        final StatefulEntryConsumer<MutableGatewayHeaders> redactingConsumer = (state, name, value) ->
        {
            if (safeHeaders.contains(name.toString().toLowerCase()))
            {
                state.add(name, value);
            }
            else
            {
                state.add(name, RedactUtil.fingerprint(value.toString()));
            }
        };
        original.forEach(redacted, redactingConsumer);
        return redacted;
    }

    private ByteBuffer cloneBuffer(final ByteBuffer original)
    {
        if (original == null)
        {
            return null;
        }
        final ByteBuffer copy = ByteBuffer.allocate(original.remaining());
        final int pos = original.position();
        copy.put(original);
        original.position(pos);
        copy.flip();
        return copy;
    }

    public long getBytesWritten()
    {
        return bytesWritten;
    }
}