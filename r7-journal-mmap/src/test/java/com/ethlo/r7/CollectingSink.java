package com.ethlo.r7;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalIntegrityListener;

/**
 * Test sink that records every outcome the journal reader can report, so assertions can
 * be made on what actually happened rather than on a single success count.
 */
public final class CollectingSink implements ExchangeCompletionListener, JournalIntegrityListener
{
    public final Map<String, JournalExchange> completed = new LinkedHashMap<>();
    public final List<String> incompleteEnds = new ArrayList<>();
    public final List<String> abandoned = new ArrayList<>();
    public final List<String> orphanedEnds = new ArrayList<>();
    public final List<String> orphanedBodies = new ArrayList<>();
    public final List<String> checksumMismatches = new ArrayList<>();

    public final List<String> corruptRegions = new ArrayList<>();
    public final List<String> quarantined = new ArrayList<>();
    public final List<String> sequenceRegressions = new ArrayList<>();
    public final List<String> recoveredSegments = new ArrayList<>();
    public final List<String> deliveryStalls = new ArrayList<>();
    /** discardedBytes per onSegmentRecovered, in call order. */
    public final List<Long> recoveryDiscardedBytes = new ArrayList<>();
    public long missingEntries;

    @Override
    public void onComplete(final JournalExchange exchange)
    {
        completed.put(exchange.getRequestId(), exchange);
    }

    @Override
    public void onIncompleteEnd(final JournalExchange exchange, final IncompleteReason reason)
    {
        incompleteEnds.add(exchange.getRequestId() + ":" + reason);
    }

    @Override
    public void onAbandoned(final JournalExchange exchange, final IncompleteReason reason)
    {
        abandoned.add(exchange.getRequestId() + ":" + reason);
    }

    @Override
    public void onOrphanedEnd(final String requestId)
    {
        orphanedEnds.add(requestId);
    }

    @Override
    public void onOrphanedBody(final String requestId, final BodyKind kind)
    {
        orphanedBodies.add(requestId + ":" + kind);
    }

    @Override
    public void onChecksumMismatch(final JournalExchange exchange, final BodyKind kind, final BodyChecksum journaled, final BodyChecksum observed)
    {
        checksumMismatches.add(exchange.getRequestId() + ":" + kind);
    }

    @Override
    public void onEntriesMissing(final String segment, final long offset, final int expectedSequence, final int foundSequence, final int missingCount)
    {
        missingEntries += missingCount;
    }

    @Override
    public void onCorruptRegion(final String segment, final long offset, final long bytesSkipped, final String reason)
    {
        corruptRegions.add(segment + "@" + offset + ":" + reason);
    }

    @Override
    public void onSequenceRegression(final String segment, final long offset, final int expectedSequence, final int foundSequence)
    {
        sequenceRegressions.add(segment + "@" + offset);
    }

    @Override
    public void onSegmentQuarantined(final String segment, final String reason)
    {
        quarantined.add(segment + ":" + reason);
    }

    @Override
    public void onSegmentRecovered(final String segment, final long dataEnd, final long discardedBytes, final long recordsRecovered)
    {
        recoveredSegments.add(segment + ":" + dataEnd + "/" + recordsRecovered + "/discarded=" + discardedBytes);
        recoveryDiscardedBytes.add(discardedBytes);
    }

    @Override
    public void onDeliveryStalled(final String segment, final long offset, final int sequence, final Throwable cause)
    {
        deliveryStalls.add(segment + "@" + offset + ":#" + sequence);
    }

    /**
     * True when nothing was lost, damaged, orphaned or left incomplete.
     */
    public boolean isClean()
    {
        return incompleteEnds.isEmpty()
                && abandoned.isEmpty()
                && orphanedEnds.isEmpty()
                && orphanedBodies.isEmpty()
                && checksumMismatches.isEmpty()
                && corruptRegions.isEmpty()
                && quarantined.isEmpty()
                && sequenceRegressions.isEmpty()
                && deliveryStalls.isEmpty()
                // A recovery that found unreadable content past the last valid entry is not
                // a clean one. Recording the number without asking about it let this oracle
                // call a lossy recovery clean, which is the one thing a test oracle may
                // never do.
                && recoveryDiscardedBytes.stream().allMatch(b -> b == 0L)
                && missingEntries == 0;
    }

    @Override
    public String toString()
    {
        return "completed=" + completed.keySet()
                + ", incompleteEnds=" + incompleteEnds
                + ", abandoned=" + abandoned
                + ", orphanedEnds=" + orphanedEnds
                + ", orphanedBodies=" + orphanedBodies
                + ", checksumMismatches=" + checksumMismatches
                + ", corruptRegions=" + corruptRegions
                + ", quarantined=" + quarantined
                + ", sequenceRegressions=" + sequenceRegressions
                + ", deliveryStalls=" + deliveryStalls
                + ", recoveryDiscardedBytes=" + recoveryDiscardedBytes
                + ", missingEntries=" + missingEntries;
    }

    /* ---------- helpers for assertions ---------- */

    public static Map<String, String> toMap(final GatewayHeaders headers)
    {
        final Map<String, String> map = new LinkedHashMap<>();
        if (headers != null)
        {
            headers.forEach(map::put);
        }
        return map;
    }

    public static byte[] concat(final List<ByteBuffer> fragments)
    {
        int total = 0;
        for (final ByteBuffer b : fragments)
        {
            total += b.remaining();
        }
        final byte[] out = new byte[total];
        int pos = 0;
        for (final ByteBuffer b : fragments)
        {
            final ByteBuffer d = b.duplicate();
            final int len = d.remaining();
            d.get(out, pos, len);
            pos += len;
        }
        return out;
    }
}
