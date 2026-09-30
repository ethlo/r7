package com.ethlo.r7.status;

import java.time.Duration;

public final class SparklineRingBuffer
{
    private final int capacity;
    private final int[] success;
    private final int[] clientError;
    private final int[] serverError;
    private final Duration interval;
    private boolean isFirstTick;

    private int head;

    // Track the absolute totals from the previous tick to calculate the delta
    private long lastSuccessTotal;
    private long lastClientErrorTotal;
    private long lastServerErrorTotal;

    public SparklineRingBuffer(final int capacity, final Duration interval)
    {
        this.capacity = capacity;
        this.success = new int[capacity];
        this.clientError = new int[capacity];
        this.serverError = new int[capacity];
        this.head = 0;
        this.isFirstTick = true;
        this.interval = interval;
    }

    public void recordTick(final long currentSuccess, final long currentClientError, final long currentServerError)
    {
        if (this.isFirstTick)
        {
            // Seed the baseline without recording a massive startup delta
            this.lastSuccessTotal = currentSuccess;
            this.lastClientErrorTotal = currentClientError;
            this.lastServerErrorTotal = currentServerError;

            this.success[this.head] = 0;
            this.clientError[this.head] = 0;
            this.serverError[this.head] = 0;

            this.isFirstTick = false;
        }
        else
        {
            // Calculate actual deltas based on the established baseline
            this.success[this.head] = (int) (currentSuccess - this.lastSuccessTotal);
            this.clientError[this.head] = (int) (currentClientError - this.lastClientErrorTotal);
            this.serverError[this.head] = (int) (currentServerError - this.lastServerErrorTotal);

            // Update last known totals
            this.lastSuccessTotal = currentSuccess;
            this.lastClientErrorTotal = currentClientError;
            this.lastServerErrorTotal = currentServerError;
        }

        // Advance ring pointer
        this.head = (this.head + 1) % this.capacity;
    }

    public SparklineSnapshot getSnapshot()
    {
        final int[] snapSuccess = new int[this.capacity];
        final int[] snapClientError = new int[this.capacity];
        final int[] snapServerError = new int[this.capacity];

        // Unwrap the ring buffer so index 0 is the oldest, and capacity-1 is the newest
        int index = this.head;
        for (int i = 0; i < this.capacity; i++)
        {
            snapSuccess[i] = this.success[index];
            snapClientError[i] = this.clientError[index];
            snapServerError[i] = this.serverError[index];
            index = (index + 1) % this.capacity;
        }

        // The newest data in the array is ALREADY the discrete count for the last interval window
        final int latestSuccess = snapSuccess[this.capacity - 1];
        final int latestClientError = snapClientError[this.capacity - 1];
        final int latestServerError = snapServerError[this.capacity - 1];

        // Calculate rates per second using the raw bucket counts
        final double intervalSeconds = Math.max(0.001, this.interval.toMillis() / 1000.0);

        final int successRate = (int) Math.round(latestSuccess / intervalSeconds);
        final int clientErrorRate = (int) Math.round(latestClientError / intervalSeconds);
        final int serverErrorRate = (int) Math.round(latestServerError / intervalSeconds);

        return new SparklineSnapshot(
                new SparklineMetadata(this.capacity, this.interval),
                successRate,
                snapSuccess,
                clientErrorRate,
                snapClientError,
                serverErrorRate,
                snapServerError
        );
    }

    public record SparklineMetadata(int capacity, Duration interval)
    {
    }

    public record SparklineSnapshot(
            SparklineMetadata metadata,
            int successRate,
            int[] success,
            int clientErrorRate,
            int[] clientError,
            int serverErrorRate,
            int[] serverError)
    {
        public int rate()
        {
            return successRate + clientErrorRate + serverErrorRate;
        }
    }

    public void hydrate(final SparklineSnapshot snapshot)
    {
        if (snapshot == null || snapshot.metadata() == null)
        {
            return;
        }

        // Safety check: Only restore the arrays if the capacity exactly matches the new YAML config.
        // If the configuration changed, we discard the old visual sparkline data, but the lifetime totals remain intact.
        if (snapshot.metadata().capacity() == this.capacity)
        {
            System.arraycopy(snapshot.success(), 0, this.success, 0, this.capacity);
            System.arraycopy(snapshot.clientError(), 0, this.clientError, 0, this.capacity);
            System.arraycopy(snapshot.serverError(), 0, this.serverError, 0, this.capacity);

            this.head = 0;
        }
        this.isFirstTick = true;
    }
}