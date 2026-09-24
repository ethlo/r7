package com.ethlo.r7.warc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps a payload digest to the WARC record that first carried that payload in full, so a later
 * record with the same bytes can be written as a {@code revisit} instead.
 * <p>
 * This is for genuine cross-exchange duplicates only — two different exchanges that happen to
 * return the same bytes (a cached response, a repeated static asset). Only records that actually
 * own a payload consult it ({@code WarcExchangeWriter}'s client request/response records); the
 * intra-exchange case (upstream leg mirrors the client leg's payload) is a different, guaranteed
 * hit rather than a probabilistic one, and per {@code design/warc.md} is handled by
 * {@code WARC-Truncated}/{@code WARC-Payload-Digest} pointer fields on those records instead of
 * a {@code revisit} record — see {@code WarcExchangeWriter}'s class javadoc for why.
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
