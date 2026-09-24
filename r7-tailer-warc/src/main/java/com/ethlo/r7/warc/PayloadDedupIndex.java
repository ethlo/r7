package com.ethlo.r7.warc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps a payload digest to the WARC record that first carried that payload in full, so a later
 * record with the same bytes can be written as a {@code revisit} instead.
 * <p>
 * Two distinct dedup opportunities share this one mechanism:
 * <ol>
 *   <li>Within a single exchange, the client and upstream legs of the same direction are
 *   guaranteed byte-identical — the gateway journal itself only stores one copy of the request
 *   body and one copy of the response body (see {@code JournalExchange}), so the second leg is
 *   always an immediate hit here.</li>
 *   <li>Across different exchanges, a repeated identical body (the same cached JSON payload,
 *   the same error page, the same static asset) is a probabilistic but very real hit, exactly
 *   the case the WARC {@code revisit}/{@code identical-payload-digest} profile exists for.</li>
 * </ol>
 * <p>
 * Bounded by entry count (not by memory, which would need to size each digest+URI+date triple)
 * so a long-running tailer process cannot grow this without limit. Eviction simply means a
 * later occurrence of an old payload is stored in full again rather than as a revisit — a
 * capacity trade-off, not a correctness one.
 */
public final class PayloadDedupIndex
{
    private final Map<String, RevisitTarget> index;

    public PayloadDedupIndex(final int maxEntries)
    {
        this.index = new LinkedHashMap<>(16, 0.75f, true)
        {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, RevisitTarget> eldest)
            {
                return size() > maxEntries;
            }
        };
    }

    synchronized RevisitTarget find(final String payloadDigest)
    {
        return index.get(payloadDigest);
    }

    synchronized void remember(final String payloadDigest, final RevisitTarget target)
    {
        index.putIfAbsent(payloadDigest, target);
    }

    /**
     * The earlier record a {@code revisit} record refers back to.
     */
    public record RevisitTarget(String recordId, String targetUri, String warcDate)
    {
    }
}
