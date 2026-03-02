package com.ethlo.venturi.journal;

import java.nio.ByteBuffer;
import java.util.Set;

import com.ethlo.venturi.RedactUtil;
import com.ethlo.venturi.api.FinishedGatewayExchange;
import com.ethlo.venturi.api.GatewayAttributes;
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
    private final Journal delegate;
    private final RouteJournalConfig config;
    private final FinishedGatewayExchange exchange;

    // --- Granular State Management ---
    private CharSequence requestId;
    private boolean clientReqFlushed = false;
    private boolean upstreamReqFlushed = false;
    private boolean upstreamResFlushed = false;
    private boolean clientResFlushed = false;

    // Metadata clones
    private ByteBuffer clientReqLine;
    private GatewayHeaders clientReqHeaders;
    private ByteBuffer upstreamReqLine;
    private GatewayHeaders upstreamReqHeaders;
    private ByteBuffer upstreamResLine;
    private GatewayHeaders upstreamResHeaders;
    private ByteBuffer clientResLine;
    private GatewayHeaders clientResHeaders;

    public PolicyJournal(final Journal delegate, final RouteJournalConfig config, final FinishedGatewayExchange exchange)
    {
        this.delegate = delegate;
        this.config = config;
        this.exchange = exchange;
    }

    @Override
    public void clientRequest(JournalLevel level, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers)
    {
        this.requestId = reqId;
        this.clientReqLine = cloneBuffer(startLine);
        this.clientReqHeaders = headers;

        // Early flush if the static config is already set to FULL
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

        if (config.request().level() == JournalLevel.FULL)
        {
            checkAndFlushRequest();
        }
    }

    @Override
    public void upstreamResponse(JournalLevel level, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers)
    {
        this.upstreamResLine = cloneBuffer(startLine);
        this.upstreamResHeaders = headers;

        // Response status is now known; resolve bilateral policy
        checkAndFlushRequest();
        checkAndFlushResponse();
    }

    @Override
    public void clientResponse(JournalLevel level, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers)
    {
        this.clientResLine = cloneBuffer(startLine);
        this.clientResHeaders = headers;

        checkAndFlushResponse();
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
        if (config.response().resolve(exchange.clientResponse().status()) == JournalLevel.FULL)
        {
            delegate.responseBody(reqId, data);
        }
    }

    @Override
    public void endExchange(CharSequence reqId, GatewayAttributes attributes, int status, long sent, long recv, long duration, long reqCrc, long resCrc)
    {
        // Final flush handles short-circuits or missed events
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
        if (clientReqFlushed && upstreamReqFlushed)
        {
            return;
        }

        final int status = exchange.clientResponse().status();
        final JournalLevel reqLevel = config.request().resolve(status);
        final JournalLevel resLevel = config.response().resolve(status);

        // Bilateral Context Promotion
        JournalLevel effectiveLevel = reqLevel;
        if (effectiveLevel == JournalLevel.NONE && resLevel != JournalLevel.NONE)
        {
            effectiveLevel = JournalLevel.METADATA;
        }

        if (effectiveLevel == JournalLevel.NONE)
        {
            return;
        }

        handleClientRequest(effectiveLevel);

        handleUpstreamRequest(effectiveLevel);
    }

    private void handleUpstreamRequest(JournalLevel effectiveLevel)
    {
        if (!upstreamReqFlushed && upstreamReqLine != null)
        {
            final GatewayHeaders headers = effectiveLevel == JournalLevel.METADATA ? FastGatewayHeaders.empty() : redactHeaders(upstreamReqHeaders, JournalSecurity.SAFE_REQUEST_HEADERS);
            delegate.upstreamRequest(effectiveLevel, requestId, upstreamReqLine, headers);
            this.upstreamReqFlushed = true;
            this.upstreamReqLine = null;
        }
    }

    private void handleClientRequest(JournalLevel effectiveLevel)
    {
        if (!clientReqFlushed && clientReqLine != null)
        {
            final GatewayHeaders headers = effectiveLevel == JournalLevel.METADATA ? FastGatewayHeaders.empty() : redactHeaders(clientReqHeaders, JournalSecurity.SAFE_REQUEST_HEADERS);
            delegate.clientRequest(effectiveLevel, requestId, clientReqLine, headers);
            this.clientReqFlushed = true;
            this.clientReqLine = null;
        }
    }

    private void checkAndFlushResponse()
    {
        // Check final response logging level based on status code config
        final int status = exchange.clientResponse().status();
        final JournalLevel resLevel = config.response().resolve(status);
        if (resLevel == JournalLevel.NONE)
        {
            return;
        }

        handleUpstreamResponse(resLevel);

        handleClientResponse(resLevel);
    }

    private void handleClientResponse(JournalLevel resLevel)
    {
        if (!clientResFlushed && clientResLine != null)
        {
            final GatewayHeaders headers = resLevel == JournalLevel.METADATA ? FastGatewayHeaders.empty() : redactHeaders(clientResHeaders, JournalSecurity.SAFE_RESPONSE_HEADERS);
            delegate.clientResponse(resLevel, requestId, clientResLine, headers);
            this.clientResFlushed = true;
            this.clientResLine = null;
        }
    }

    private void handleUpstreamResponse(JournalLevel resLevel)
    {
        if (!upstreamResFlushed && upstreamResLine != null)
        {
            final GatewayHeaders headers = resLevel == JournalLevel.METADATA ? FastGatewayHeaders.empty() : redactHeaders(upstreamResHeaders, JournalSecurity.SAFE_RESPONSE_HEADERS);
            delegate.upstreamResponse(resLevel, requestId, upstreamResLine, headers);
            this.upstreamResFlushed = true;
            this.upstreamResLine = null;
        }
    }

    private GatewayHeaders redactHeaders(final GatewayHeaders original, Set<String> safeHeaders)
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
}