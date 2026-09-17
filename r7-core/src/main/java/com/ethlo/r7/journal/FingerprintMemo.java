package com.ethlo.r7.journal;

import com.ethlo.r7.util.RedactUtil;

/**
 * Remembers the fingerprints computed for one exchange, so that a value appearing in more than
 * one journaled message is hashed once.
 * <p>
 * An exchange journals its request headers twice — once as it arrived from the client, once as
 * it was sent upstream — and its response headers twice for the same reason. A header no filter
 * rewrote is the <em>same String instance</em> in both, because the ingress snapshot copies
 * references out of the live header map rather than the characters. So identity is enough to
 * recognise the repeat, and it costs a pointer comparison against a table that holds only the
 * values actually fingerprinted, which is the unsafe ones.
 * <p>
 * Keyed on identity rather than equality deliberately. Equality would hit slightly more often
 * and cost a full string comparison to find out; identity cannot produce a wrong answer, since
 * a fingerprint is a pure function of the value and the same instance is the same value.
 * <p>
 * Per exchange, and must stay that way: the values are request data, and a table that outlived
 * the exchange would both grow without bound and hold that data alive.
 */
final class FingerprintMemo
{
    /**
     * Beyond this the table stops growing and later values are hashed every time. A linear
     * scan is the right structure for the handful of unsafe headers a normal request carries;
     * the cap is what stops a request with hundreds of them from turning it into the wrong one.
     */
    private static final int MAX_ENTRIES = 32;

    private String[] values = new String[8];
    private String[] fingerprints = new String[8];
    private int size;

    String fingerprintOf(final String value)
    {
        for (int i = 0; i < size; i++)
        {
            if (values[i] == value)
            {
                return fingerprints[i];
            }
        }

        final String fingerprint = RedactUtil.fingerprint(value);

        if (size < MAX_ENTRIES)
        {
            if (size == values.length)
            {
                grow();
            }
            values[size] = value;
            fingerprints[size] = fingerprint;
            size++;
        }
        return fingerprint;
    }

    private void grow()
    {
        final int capacity = Math.min(values.length * 2, MAX_ENTRIES);
        final String[] grownValues = new String[capacity];
        final String[] grownFingerprints = new String[capacity];
        System.arraycopy(values, 0, grownValues, 0, size);
        System.arraycopy(fingerprints, 0, grownFingerprints, 0, size);
        values = grownValues;
        fingerprints = grownFingerprints;
    }
}
