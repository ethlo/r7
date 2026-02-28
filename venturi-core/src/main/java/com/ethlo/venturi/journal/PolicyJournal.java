package com.ethlo.venturi.journal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.MutableGatewayHeaders;
import com.ethlo.venturi.api.StatefulEntryConsumer;
import com.ethlo.venturi.config.RouteJournalConfig;
import com.ethlo.venturi.journal.api.Journal;
import com.ethlo.venturi.journal.api.JournalLevel;
import com.ethlo.venturi.util.FastGatewayHeaders;
import com.ethlo.venturi.util.MutableFastGatewayHeaders;

public final class PolicyJournal implements Journal
{
    private static final byte[] HEX_ARRAY = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() ->
    {
        try
        {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (final NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }
    });

    private final Journal delegate;
    private final RouteJournalConfig config;
    private final Set<CharSequence> safeHeaders = new HashSet<>();
    private final GatewayExchange exchange;

    private final StatefulEntryConsumer<MutableGatewayHeaders> redactingConsumer = (state, name, value) ->
    {
        if (safeHeaders.contains(name))
        {
            state.add(name, value);
        }
        else
        {
            state.add(name, fingerprint(value.toString()));
        }
    };

    // --- State Management for Status-Based Decision ---
    private CharSequence requestId;
    private boolean reqFlushed = false;
    private boolean resFlushed = false;

    // We keep clones of the metadata until we know the final status code
    private ByteBuffer clientReqLine;
    private GatewayHeaders clientReqHeaders;
    private ByteBuffer upstreamReqLine;
    private GatewayHeaders upstreamReqHeaders;
    private ByteBuffer upstreamResLine;
    private GatewayHeaders upstreamResHeaders;
    private ByteBuffer clientResLine;
    private GatewayHeaders clientResHeaders;

    public PolicyJournal(final Journal delegate, final RouteJournalConfig config, final Collection<String> safeHeaders, final GatewayExchange exchange)
    {
        this.delegate = delegate;
        this.config = config;
        this.exchange = exchange;

        for (final String header : safeHeaders)
        {
            this.safeHeaders.add(header.toLowerCase());
        }
    }

    @Override
    public void clientRequest(JournalLevel level, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers)
    {
        this.requestId = reqId;
        this.clientReqLine = cloneBuffer(startLine);
        this.clientReqHeaders = headers;

        // If the route is configured for mandatory FULL logging, we can flush early
        if (config.request().level() == JournalLevel.FULL)
        {
            checkAndFlushRequest();
        }
    }

    @Override
    public void upstreamRequest(JournalLevel level, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers)
    {
        this.upstreamReqLine = cloneBuffer(startLine);
        this.upstreamReqHeaders = headers;
    }

    @Override
    public void upstreamResponse(JournalLevel level, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers)
    {
        this.upstreamResLine = cloneBuffer(startLine);
        this.upstreamResHeaders = headers;

        // Now that we have a response status, we might be able to decide the logging level
        checkAndFlushRequest();
        checkAndFlushResponse();
    }

    @Override
    public void clientResponse(JournalLevel level, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers)
    {
        this.clientResLine = cloneBuffer(startLine);
        this.clientResHeaders = headers;
    }

    @Override
    public void requestBody(CharSequence reqId, ByteBuffer data)
    {
        if (config.request().level() == JournalLevel.FULL)
        {
            delegate.requestBody(reqId, data);
        }
    }

    @Override
    public void responseBody(CharSequence reqId, ByteBuffer data)
    {
        // Response body logging is almost always status-dependent
        if (config.response().resolve(exchange.response().status()) == JournalLevel.FULL)
        {
            delegate.responseBody(reqId, data);
        }
    }

    @Override
    public void endExchange(CharSequence reqId, GatewayAttributes attributes, int status, long sent, long recv, long duration, long reqCrc, long resCrc)
    {
        // Final safety flush in case of early errors or short-circuits
        checkAndFlushRequest();
        checkAndFlushResponse();

        delegate.endExchange(reqId, attributes, status, sent, recv, duration, reqCrc, resCrc);
    }

    @Override
    public void close() throws java.io.IOException
    {
        delegate.close();
    }

    private void checkAndFlushRequest()
    {
        if (reqFlushed || clientReqLine == null) return;

        final int status = exchange.response().status();
        final JournalLevel reqLevel = config.request().resolve(status);
        final JournalLevel resLevel = config.response().resolve(status);

        // Bilateral Context Promotion: If response is logged, we need request metadata
        JournalLevel effectiveLevel = reqLevel;
        if (effectiveLevel == JournalLevel.NONE && resLevel != JournalLevel.NONE)
        {
            effectiveLevel = JournalLevel.METADATA;
        }

        if (effectiveLevel != JournalLevel.NONE)
        {
            delegate.clientRequest(effectiveLevel, requestId, clientReqLine,
                    effectiveLevel == JournalLevel.METADATA ? FastGatewayHeaders.empty() : redact(clientReqHeaders)
            );

            if (upstreamReqLine != null)
            {
                delegate.upstreamRequest(effectiveLevel, requestId, upstreamReqLine,
                        effectiveLevel == JournalLevel.METADATA ? FastGatewayHeaders.empty() : redact(upstreamReqHeaders)
                );
            }
            this.reqFlushed = true;
        }
    }

    private void checkAndFlushResponse()
    {
        if (resFlushed || upstreamResLine == null) return;

        final int status = exchange.response().status();
        final JournalLevel resLevel = config.response().resolve(status);

        if (resLevel != JournalLevel.NONE)
        {
            delegate.upstreamResponse(resLevel, requestId, upstreamResLine,
                    resLevel == JournalLevel.METADATA ? FastGatewayHeaders.empty() : redact(upstreamResHeaders)
            );

            if (clientResLine != null)
            {
                delegate.clientResponse(resLevel, requestId, clientResLine,
                        resLevel == JournalLevel.METADATA ? FastGatewayHeaders.empty() : redact(clientResHeaders)
                );
            }
            this.resFlushed = true;
        }
    }

    private GatewayHeaders redact(final GatewayHeaders original)
    {
        if (original == null) return FastGatewayHeaders.empty();
        final MutableGatewayHeaders redacted = new MutableFastGatewayHeaders();
        original.forEach(redacted, this.redactingConsumer);
        return redacted;
    }

    private String fingerprint(final String value)
    {
        final MessageDigest digest = SHA_256.get();
        digest.reset();
        final byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        final char[] out = new char[16];
        "id:sha256:".getChars(0, 10, out, 0);
        for (int j = 0; j < 3; j++)
        {
            final int v = hash[j] & 0xFF;
            out[10 + j * 2] = (char) HEX_ARRAY[v >>> 4];
            out[11 + j * 2] = (char) HEX_ARRAY[v & 0x0F];
        }
        return new String(out);
    }

    private ByteBuffer cloneBuffer(final ByteBuffer original)
    {
        if (original == null) return null;
        final ByteBuffer copy = ByteBuffer.allocate(original.remaining());
        final int pos = original.position();
        copy.put(original);
        original.position(pos);
        copy.flip();
        return copy;
    }
}