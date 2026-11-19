package com.ethlo.r7.warc;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Computes a WARC {@code labelled-digest} (e.g. {@code sha256:AB2CD...}) over a payload.
 * <p>
 * Per the WARC 1.1 spec, "the payload of an application/http block is its entity-body" — the
 * digest is taken over the body only, never the HTTP header block. That is exactly what makes
 * it usable as a dedup key across two records whose headers differ (client vs. upstream leg,
 * or two different requests that happen to return the same bytes) but whose bodies do not.
 */
final class PayloadDigest
{
    private static final String ALGORITHM_LABEL = "sha256";
    private static final String ALGORITHM_NAME = "SHA-256";

    private PayloadDigest()
    {
    }

    /**
     * @return the labelled digest of the concatenated fragments, or {@code null} if no body was
     * captured for this leg at all (an empty list) — used as the signal to skip dedup/payload
     * handling entirely, e.g. a GET request or a journal level below FULL.
     */
    static String of(final List<ByteBuffer> fragments)
    {
        if (fragments == null || fragments.isEmpty())
        {
            return null;
        }

        final MessageDigest digest;
        try
        {
            digest = MessageDigest.getInstance(ALGORITHM_NAME);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(ALGORITHM_NAME + " is a JDK-mandatory algorithm and must always be available", e);
        }

        for (final ByteBuffer fragment : fragments)
        {
            digest.update(fragment.duplicate());
        }

        return ALGORITHM_LABEL + ":" + Base32.encode(digest.digest());
    }
}
