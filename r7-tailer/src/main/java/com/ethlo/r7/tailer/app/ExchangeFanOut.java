package com.ethlo.r7.tailer.app;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.json.JsonLinesWriter;
import com.ethlo.r7.warc.WarcExchangeWriter;

/**
 * Hands each exchange to the enabled outputs: WARC first, then the JSON line, which points at
 * the WARC records instead of carrying the bodies.
 * <p>
 * <b>One exchange, one unit.</b> The tailer offers an exchange again when this listener throws,
 * so a JSON line that fails after the WARC records were written must take those records back:
 * otherwise every retry would archive the exchange once more. The WARC records are committed
 * (the dedup index learns their payloads) only once the JSON line is written too.
 * <p>
 * <b>Unless the line may have got out.</b> Standard output can deliver a whole line and still
 * report a failure. Taking the records back then would let later records overwrite the bytes a
 * delivered line's {@code warc} pointer names, so they are kept and committed instead, and the
 * retry of the same exchange points at them rather than archiving it again.
 * <p>
 * Incomplete and abandoned exchanges go to the JSON output only, with their bodies: a WARC file
 * holds complete exchanges.
 */
final class ExchangeFanOut implements ExchangeCompletionListener
{
    private static final Logger logger = LoggerFactory.getLogger(ExchangeFanOut.class);

    private final WarcExchangeWriter warc;
    private final WarcOutputConfig.Exchanges warcExchanges;
    private final JsonLinesWriter json;

    /**
     * The most exchanges whose records are kept for a retry at once. The reader moves on to other
     * shards after a refused entry, so retries of several exchanges interleave; one per shard
     * is the usual need. Past this, the oldest is forgotten and its retry archives it again.
     */
    static final int MAX_KEPT = 1024;

    /**
     * The records of exchanges whose line may have got out, by request ID, kept until a line of
     * theirs is written.
     */
    private final Map<String, WarcExchangeWriter.Written> kept = new LinkedHashMap<>(16, 0.75f, false)
    {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, WarcExchangeWriter.Written> eldest)
        {
            return size() > MAX_KEPT;
        }
    };

    /**
     * @param warc null when WARC output is off
     * @param json null when JSON output is off
     */
    ExchangeFanOut(final WarcExchangeWriter warc, final WarcOutputConfig.Exchanges warcExchanges, final JsonLinesWriter json)
    {
        if (warc == null && json == null)
        {
            throw new IllegalArgumentException("No output enabled");
        }
        this.warc = warc;
        this.warcExchanges = warcExchanges;
        this.json = json;
    }

    @Override
    public void onComplete(final JournalExchange exchange)
    {
        WarcExchangeWriter.Written written = kept.get(exchange.getRequestId());
        final boolean reused = written != null;
        if (reused)
        {
            // The records hold this exchange already; only the retry's checksum state is left.
            warc.skip(exchange);
        }
        else if (warc != null)
        {
            if (archived(exchange))
            {
                written = warc.write(exchange);
            }
            else
            {
                warc.skip(exchange);
            }
        }
        if (json != null)
        {
            try
            {
                json.writeComplete(exchange, written != null
                        ? new JsonLinesWriter.WarcPointer(written.location().file(), written.location().offset(), written.location().length())
                        : null, warc != null && warc.storesBodies());
            }
            catch (final JsonLinesWriter.LineMayHaveGotOutException e)
            {
                if (written != null && !reused)
                {
                    written.commit();
                    kept.put(exchange.getRequestId(), written);
                }
                throw e;
            }
            catch (final RuntimeException e)
            {
                // Records reused from an earlier attempt were committed then, and are not the
                // file's last write to take back.
                if (written != null && !reused)
                {
                    discard(exchange, written, e);
                }
                throw e;
            }
        }
        if (reused)
        {
            kept.remove(exchange.getRequestId());
        }
        else if (written != null)
        {
            written.commit();
        }
    }

    private void discard(final JournalExchange exchange, final WarcExchangeWriter.Written written, final RuntimeException cause)
    {
        try
        {
            warc.discard(written);
        }
        catch (final RuntimeException e)
        {
            // The cut-back is retried before the WARC file takes another record or is sealed, so
            // the retry of this exchange stalls until it succeeds rather than archive it twice.
            cause.addSuppressed(e);
            logger.error("Could not take back the WARC records of exchange {} after its JSON line failed", exchange.getRequestId(), e);
        }
    }

    private boolean archived(final JournalExchange exchange)
    {
        return warcExchanges == WarcOutputConfig.Exchanges.ALL
                || hasBytes(exchange.getRequestBodyFragments())
                || hasBytes(exchange.getResponseBodyFragments());
    }

    private static boolean hasBytes(final List<ByteBuffer> fragments)
    {
        if (fragments != null)
        {
            for (final ByteBuffer fragment : fragments)
            {
                if (fragment.hasRemaining())
                {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void onIncompleteEnd(final JournalExchange exchange, final IncompleteReason reason)
    {
        if (warc != null)
        {
            warc.onIncompleteEnd(exchange, reason);
        }
        if (json != null)
        {
            json.onIncompleteEnd(exchange, reason);
        }
    }

    @Override
    public void onAbandoned(final JournalExchange exchange, final IncompleteReason reason)
    {
        if (warc != null)
        {
            warc.onAbandoned(exchange, reason);
        }
        if (json != null)
        {
            json.onAbandoned(exchange, reason);
        }
    }

    @Override
    public void onOrphanedEnd(final String requestId)
    {
        if (json != null)
        {
            json.onOrphanedEnd(requestId);
        }
        else
        {
            warc.onOrphanedEnd(requestId);
        }
    }

    @Override
    public void onOrphanedBody(final String requestId, final BodyKind kind)
    {
        if (json != null)
        {
            json.onOrphanedBody(requestId, kind);
        }
        else
        {
            warc.onOrphanedBody(requestId, kind);
        }
    }

    @Override
    public void onChecksumMismatch(final JournalExchange exchange, final BodyKind kind, final BodyChecksum journaled, final BodyChecksum observed)
    {
        if (warc != null)
        {
            warc.onChecksumMismatch(exchange, kind, journaled, observed);
        }
        if (json != null)
        {
            json.onChecksumMismatch(exchange, kind, journaled, observed);
        }
    }
}
