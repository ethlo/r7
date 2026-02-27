package com.ethlo.venturi.journal;

import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;
import com.ethlo.venturi.api.StatefulEntryConsumer;
import com.ethlo.venturi.config.RouteJournalConfig;
import com.ethlo.venturi.journal.api.Journal;
import com.ethlo.venturi.journal.api.JournalLevel;
import com.ethlo.venturi.util.FastGatewayHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

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
    private final Set<String> safeHeaders;

    // Pre-allocate the consumer to avoid capturing-lambda allocations on the hot path
    private final StatefulEntryConsumer<GatewayHeaders> redactingConsumer = new StatefulEntryConsumer<>()
    {
        @Override
        public void accept(final GatewayHeaders state, final CharSequence name, final CharSequence value)
        {
            // Note: toString().toLowerCase() does allocate. If absolute zero-allocation
            // is required here, a custom CharSequence-aware hashing map is needed.
            final String lowerName = name.toString().toLowerCase();

            if (safeHeaders.contains(lowerName))
            {
                state.add(name, value);
            }
            else
            {
                state.add(name, fingerprint(value.toString()));
            }
        }
    };

    public PolicyJournal(final Journal delegate, final RouteJournalConfig config, final Set<String> safeHeaders)
    {
        this.delegate = delegate;
        this.config = config;
        this.safeHeaders = safeHeaders;
    }

    @Override
    public void start(final ServerDirection direction, final JournalLevel level, final CharSequence requestId, final ByteBuffer startLine, final GatewayHeaders headers)
    {
        final JournalLevel configuredLevel = getLevelForDirection(direction);

        if (configuredLevel == JournalLevel.NONE)
        {
            return;
        }

        if (configuredLevel == JournalLevel.METADATA)
        {
            delegate.start(direction, configuredLevel, requestId, startLine, FastGatewayHeaders.empty());
            return;
        }

        final GatewayHeaders redactedHeaders = redact(headers);
        delegate.start(direction, configuredLevel, requestId, startLine, redactedHeaders);
    }

    @Override
    public void body(final ServerDirection direction, final CharSequence requestId, final ByteBuffer buffer)
    {
        final JournalLevel configuredLevel = getLevelForDirection(direction);

        if (configuredLevel == JournalLevel.FULL)
        {
            delegate.body(direction, requestId, buffer);
        }
    }

    @Override
    public void end(final CharSequence reqId, final GatewayAttributes attributes, final int status, final long sent, final long recv, final long duration)
    {
        delegate.end(reqId, attributes, status, sent, recv, duration);
    }

    @Override
    public void close()
    {
        // Nothing to close
    }

    private JournalLevel getLevelForDirection(final ServerDirection direction)
    {
        if (direction == ServerDirection.REQUEST)
        {
            return config.request();
        }
        else
        {
            return config.response();
        }
    }

    private GatewayHeaders redact(final GatewayHeaders original)
    {
        final GatewayHeaders redacted = new FastGatewayHeaders();
        // Pass the new headers object as the state, avoiding lambda closure allocation
        original.forEach(redacted, this.redactingConsumer);
        return redacted;
    }

    private String fingerprint(final String value)
    {
        final MessageDigest digest = SHA_256.get();
        digest.reset();

        final byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));

        final byte[] hexChars = new byte[6];
        for (int j = 0; j < 3; j++)
        {
            final int v = hash[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }

        return "[REDACTED - SHA256:" + new String(hexChars, StandardCharsets.US_ASCII) + "]";
    }
}