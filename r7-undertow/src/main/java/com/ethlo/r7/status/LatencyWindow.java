package com.ethlo.r7.status;

import java.util.concurrent.atomic.LongAdder;

import com.ethlo.r7.status.dto.LatencyDto;

/**
 * Response-time distribution over the same sliding window as the traffic sparkline.
 * <p>
 * Requests only ever increment a cumulative {@link LongAdder} per bucket; the scheduler tick turns
 * the difference since the previous tick into one ring slot, the way {@link SparklineRingBuffer}
 * does for status counts. A lifetime average - all the dashboard had before - never moves once a
 * route has served a few million requests, so it cannot show that a route got slow ten minutes ago.
 * <p>
 * Not persisted across restarts: like the sparkline's first tick, the window starts empty.
 */
public final class LatencyWindow
{
    private static final int BUCKETS = LatencyHistogram.BUCKET_COUNT;

    private final LongAdder[] cumulative = new LongAdder[BUCKETS];
    private final long[] lastCumulative = new long[BUCKETS];
    private final int[] ring;
    private final int capacity;
    private int head;

    public LatencyWindow(final int capacity)
    {
        this.capacity = capacity;
        this.ring = new int[capacity * BUCKETS];
        for (int i = 0; i < BUCKETS; i++)
        {
            this.cumulative[i] = new LongAdder();
        }
    }

    public void record(final long durationNanos)
    {
        this.cumulative[LatencyHistogram.bucketOf(durationNanos)].increment();
    }

    public void merge(final LatencyWindow other)
    {
        for (int i = 0; i < BUCKETS; i++)
        {
            this.cumulative[i].add(other.cumulative[i].sumThenReset());
        }
    }

    public void tick()
    {
        final int base = this.head * BUCKETS;
        for (int i = 0; i < BUCKETS; i++)
        {
            final long current = this.cumulative[i].sum();
            this.ring[base + i] = (int) (current - this.lastCumulative[i]);
            this.lastCumulative[i] = current;
        }
        this.head = (this.head + 1) % this.capacity;
    }

    public LatencyDto snapshot()
    {
        final long[] window = new long[BUCKETS];
        final long[] slot = new long[BUCKETS];
        final long[] p99Series = new long[this.capacity];

        // Oldest slot first, so the series lines up with the sparkline arrays
        int index = this.head;
        for (int t = 0; t < this.capacity; t++)
        {
            final int base = index * BUCKETS;
            for (int i = 0; i < BUCKETS; i++)
            {
                slot[i] = this.ring[base + i];
                window[i] += slot[i];
            }
            p99Series[t] = LatencyHistogram.quantileMicros(slot, 0.99);
            index = (index + 1) % this.capacity;
        }

        long samples = 0;
        for (final long count : window)
        {
            samples += count;
        }

        return new LatencyDto(
                samples,
                LatencyHistogram.quantileMicros(window, 0.50),
                LatencyHistogram.quantileMicros(window, 0.95),
                LatencyHistogram.quantileMicros(window, 0.99),
                p99Series
        );
    }
}
