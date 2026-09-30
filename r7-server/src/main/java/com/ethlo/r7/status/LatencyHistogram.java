package com.ethlo.r7.status;

/**
 * Bucket arithmetic for the response-time histogram kept per route.
 * <p>
 * Four sub-buckets per power of two of microseconds: an index is a couple of shifts away from the
 * duration, so recording costs one {@code LongAdder} increment and no allocation, and a reported
 * percentile is at most 25% above the true value. That is resolution enough to tell 2ms from 20ms,
 * which is what an operator looks at a gateway's latency for; it is not a benchmark instrument.
 * <p>
 * Values from 1µs to just under 2<sup>24</sup>µs (~16.8s) get their own bucket; anything slower
 * falls into the last one, whose percentile is reported as that upper bound.
 */
public final class LatencyHistogram
{
    private static final int SUB_BUCKET_BITS = 2;
    private static final int SUB_BUCKETS = 1 << SUB_BUCKET_BITS;
    private static final int MAX_EXPONENT = 23;

    /**
     * Buckets 0-3 are 0-3µs exactly; then four per octave up to {@link #MAX_EXPONENT}; then overflow.
     */
    public static final int BUCKET_COUNT = (MAX_EXPONENT - 1) * SUB_BUCKETS + SUB_BUCKETS + 1;

    private LatencyHistogram()
    {
    }

    public static int bucketOf(final long durationNanos)
    {
        final long micros = Math.max(0, durationNanos / 1000);
        if (micros < SUB_BUCKETS)
        {
            return (int) micros;
        }
        final int exponent = 63 - Long.numberOfLeadingZeros(micros);
        if (exponent > MAX_EXPONENT)
        {
            return BUCKET_COUNT - 1;
        }
        final int subBucket = (int) (micros >>> (exponent - SUB_BUCKET_BITS)) & (SUB_BUCKETS - 1);
        return (exponent - 1) * SUB_BUCKETS + subBucket;
    }

    /**
     * The exclusive upper bound of a bucket, in microseconds: the value reported for a percentile
     * that lands in it, so a reported p99 is never lower than the real one.
     */
    public static long upperBoundMicros(final int bucket)
    {
        if (bucket < SUB_BUCKETS)
        {
            return bucket + 1;
        }
        if (bucket >= BUCKET_COUNT - 1)
        {
            return 1L << (MAX_EXPONENT + 1);
        }
        final int exponent = bucket / SUB_BUCKETS + 1;
        final int subBucket = bucket % SUB_BUCKETS;
        return (long) (SUB_BUCKETS + subBucket + 1) << (exponent - SUB_BUCKET_BITS);
    }

    /**
     * @return the upper bound in microseconds of the bucket holding the given quantile, or 0 when
     * there are no samples
     */
    public static long quantileMicros(final long[] counts, final double quantile)
    {
        long total = 0;
        for (final long count : counts)
        {
            total += count;
        }
        if (total == 0)
        {
            return 0;
        }
        final long rank = Math.max(1, (long) Math.ceil(total * quantile));
        long seen = 0;
        for (int i = 0; i < counts.length; i++)
        {
            seen += counts[i];
            if (seen >= rank)
            {
                return upperBoundMicros(i);
            }
        }
        return upperBoundMicros(counts.length - 1);
    }
}
