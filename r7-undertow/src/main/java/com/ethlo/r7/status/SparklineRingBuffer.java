package com.ethlo.r7.status;

import com.fasterxml.jackson.annotation.JsonProperty;

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

    // Track the absolute totals from the previous tick to calculate the 10-second delta
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

        int index = this.head;
        for (int i = 0; i < this.capacity; i++)
        {
            snapSuccess[i] = this.success[index];
            snapClientError[i] = this.clientError[index];
            snapServerError[i] = this.serverError[index];
            index = (index + 1) % this.capacity;
        }

        return new SparklineSnapshot(new SparklineMetadata(capacity, interval), snapSuccess, snapClientError, snapServerError);
    }

    public record SparklineMetadata(int capacity, Duration interval)
    {
    }

    public record SparklineSnapshot(
            SparklineMetadata metadata,
            int[] success,
            @JsonProperty("client_error")
            int[] clientError,
            @JsonProperty("server_error")
            int[] serverError)
    {
    }
}