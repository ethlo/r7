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
import com.ethlo.venturi.api.ServerDirection;
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
    private final Set<CharSequence> safeHeaders;
    private final GatewayExchange exchange;
    private final StatefulEntryConsumer<MutableGatewayHeaders> redactingConsumer = new StatefulEntryConsumer<>()
    {
        @Override
        public void accept(final MutableGatewayHeaders state, final CharSequence name, final CharSequence value)
        {
            if (safeHeaders.contains(name))
            {
                state.add(name, value);
            }
            else
            {
                state.add(name, fingerprint(value.toString()));
            }
        }
    };
    // --- Request-Scoped State ---
    private CharSequence requestId;
    private boolean reqStartHandled = false;
    private ByteBuffer reqStartLine;
    private GatewayHeaders reqHeaders;
    private boolean resStartHandled = false;
    private ByteBuffer resStartLine;
    private GatewayHeaders resHeaders;

    public PolicyJournal(final Journal delegate, final RouteJournalConfig config, final Collection<String> safeHeaders, final GatewayExchange exchange)
    {
        this.delegate = delegate;
        this.config = config;
        this.exchange = exchange;

        this.safeHeaders = new HashSet<>(safeHeaders.size());
        for (final String header : safeHeaders)
        {
            this.safeHeaders.add(header.toLowerCase());
        }
    }

    @Override
    public void start(final ServerDirection direction, final JournalLevel ignoredLevel, final CharSequence reqId, final ByteBuffer startLine, final GatewayHeaders headers)
    {
        this.requestId = reqId;

        if (direction == ServerDirection.REQUEST)
        {
            this.reqStartLine = cloneBuffer(startLine);
            this.reqHeaders = headers;

            if (config.request().level() == JournalLevel.FULL)
            {
                // If request is FULL, we don't care what the response is yet.
                flushRequestStart(JournalLevel.FULL, JournalLevel.NONE);
            }
        }
        else
        {
            this.resStartLine = cloneBuffer(startLine);
            this.resHeaders = headers;

            final int status = exchange.response().status();
            final JournalLevel finalReqLevel = config.request().resolve(status);
            final JournalLevel finalResLevel = config.response().resolve(status);

            if (!reqStartHandled)
            {
                flushRequestStart(finalReqLevel, finalResLevel);
            }

            flushResponseStart(finalResLevel);
        }
    }

    @Override
    public void end(final CharSequence reqId, final GatewayAttributes attributes, final int status, final long sent, final long recv, final long duration)
    {
        final JournalLevel finalReqLevel = config.request().resolve(status);
        final JournalLevel finalResLevel = config.response().resolve(status);

        if (!reqStartHandled && reqStartLine != null)
        {
            flushRequestStart(finalReqLevel, finalResLevel);
        }

        if (!resStartHandled && resStartLine != null)
        {
            flushResponseStart(finalResLevel);
        }

        delegate.end(reqId, attributes, status, sent, recv, duration);
    }

    @Override
    public void body(final ServerDirection direction, final CharSequence reqId, final ByteBuffer buffer)
    {
        final JournalLevel level;

        if (direction == ServerDirection.REQUEST)
        {
            // Overrides cannot elevate to FULL, so the base level is mathematically safe here.
            level = config.request().level();
        }
        else
        {
            level = config.response().resolve(exchange.response().status());
        }

        if (level == JournalLevel.FULL)
        {
            delegate.body(direction, reqId, buffer);
        }
    }

    @Override
    public void close()
    {
        // Nothing to close
    }

    private void flushRequestStart(final JournalLevel reqLevel, final JournalLevel resLevel)
    {
        this.reqStartHandled = true;

        JournalLevel effectiveReqLevel = reqLevel;

        // If the response is being logged, we MUST have at least the request metadata
        // so the tailer knows the Method and URI.
        if (effectiveReqLevel == JournalLevel.NONE && resLevel != JournalLevel.NONE)
        {
            effectiveReqLevel = JournalLevel.METADATA;
        }

        if (effectiveReqLevel == JournalLevel.NONE)
        {
            return;
        }

        if (effectiveReqLevel == JournalLevel.METADATA)
        {
            delegate.start(ServerDirection.REQUEST, effectiveReqLevel, requestId, reqStartLine, FastGatewayHeaders.empty());
        }
        else
        {
            delegate.start(ServerDirection.REQUEST, effectiveReqLevel, requestId, reqStartLine, redact(reqHeaders));
        }
    }

    private void flushResponseStart(final JournalLevel resolvedLevel)
    {
        this.resStartHandled = true;
        if (resolvedLevel == JournalLevel.NONE)
        {
            return;
        }

        if (resolvedLevel == JournalLevel.METADATA)
        {
            delegate.start(ServerDirection.RESPONSE, resolvedLevel, requestId, resStartLine, FastGatewayHeaders.empty());
        }
        else
        {
            delegate.start(ServerDirection.RESPONSE, resolvedLevel, requestId, resStartLine, redact(resHeaders));
        }
    }

    private GatewayHeaders redact(final GatewayHeaders original)
    {
        final MutableGatewayHeaders redacted = new MutableFastGatewayHeaders();
        original.forEach(redacted, this.redactingConsumer);
        return redacted;
    }

    private String fingerprint(final String value)
    {
        final MessageDigest digest = SHA_256.get();
        digest.reset();
        final byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));

        // "id:sha256:" is 10 chars + 6 hex chars = 16 chars total
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

        // Allocate a new buffer on the heap safely sized to the remaining bytes
        final ByteBuffer copy = ByteBuffer.allocate(original.remaining());

        // Mark the original position so we don't mutate the source buffer's state
        final int pos = original.position();

        copy.put(original);
        original.position(pos); // Restore original

        copy.flip(); // Prepare our clone for reading by the delegate
        return copy;
    }
}