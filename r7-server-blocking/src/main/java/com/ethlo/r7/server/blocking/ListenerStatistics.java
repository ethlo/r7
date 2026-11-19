package com.ethlo.r7.server.blocking;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

import com.ethlo.r7.status.dto.ConnectorStatisticsDto;

/**
 * The data-plane listener's counters for the management port: an error is a response with status
 * 500, and processing time runs from the request's arrival to its completion. Bytes are what the
 * exchange carried, head and body - not what crossed the socket, which differs by framing (chunk
 * sizes, TLS records).
 * <p>
 * Requests are counted by {@link BlockingGateway}. Connections only by a server that reports them
 * through {@link #connectionOpened()} and {@link #connectionClosed()}; a servlet container does not,
 * and there they stay 0.
 * <p>
 * Totals are {@link LongAdder}s, which do not contend. The two in-flight gauges are single atomics,
 * as their peaks need the exact current value; that is one contended increment and decrement per
 * request and per connection.
 */
public final class ListenerStatistics
{
    private final LongAdder requestCount = new LongAdder();
    private final LongAdder bytesSent = new LongAdder();
    private final LongAdder bytesReceived = new LongAdder();
    private final LongAdder errorCount = new LongAdder();
    private final LongAdder processingNanos = new LongAdder();
    private final LongAccumulator maxProcessingNanos = new LongAccumulator(Math::max, 0);
    private final AtomicLong activeConnections = new AtomicLong();
    private final LongAccumulator maxActiveConnections = new LongAccumulator(Math::max, 0);
    private final AtomicLong activeRequests = new AtomicLong();
    private final LongAccumulator maxActiveRequests = new LongAccumulator(Math::max, 0);

    public void connectionOpened()
    {
        this.maxActiveConnections.accumulate(this.activeConnections.incrementAndGet());
    }

    public void connectionClosed()
    {
        this.activeConnections.decrementAndGet();
    }

    void requestStarted()
    {
        this.requestCount.increment();
        this.maxActiveRequests.accumulate(this.activeRequests.incrementAndGet());
    }

    void requestFinished(final long nanos, final int status, final long sent, final long received)
    {
        this.activeRequests.decrementAndGet();
        this.processingNanos.add(nanos);
        this.maxProcessingNanos.accumulate(nanos);
        this.bytesSent.add(sent);
        this.bytesReceived.add(received);
        if (status == 500)
        {
            this.errorCount.increment();
        }
    }

    public ConnectorStatisticsDto snapshot()
    {
        return new ConnectorStatisticsDto(
                this.requestCount.sum(),
                this.bytesSent.sum(),
                this.bytesReceived.sum(),
                this.errorCount.sum(),
                this.processingNanos.sum(),
                this.maxProcessingNanos.get(),
                this.activeConnections.get(),
                this.maxActiveConnections.get(),
                this.activeRequests.get(),
                this.maxActiveRequests.get());
    }
}
