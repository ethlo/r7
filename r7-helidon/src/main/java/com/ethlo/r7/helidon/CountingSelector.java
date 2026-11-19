package com.ethlo.r7.helidon;

import java.time.Duration;
import java.util.Set;

import com.ethlo.r7.server.blocking.ListenerStatistics;
import io.helidon.common.buffers.BufferData;
import io.helidon.common.concurrency.limits.Limit;
import io.helidon.common.task.InterruptableTask;
import io.helidon.webserver.ConnectionContext;
import io.helidon.webserver.spi.ServerConnection;
import io.helidon.webserver.spi.ServerConnectionSelector;

/**
 * One of Helidon's protocol selectors, with the connections it accepts counted for the
 * management port. Helidon keeps no connection counters it will share, but every connection runs
 * inside one call to {@link ServerConnection#handle(Limit)}, so that call brackets its lifetime.
 * Selectors a listener is given run before the ones it derives from its protocols, so a wrapped
 * selector takes every connection the unwrapped one would have.
 */
final class CountingSelector implements ServerConnectionSelector
{
    private final ServerConnectionSelector delegate;
    private final ListenerStatistics statistics;

    CountingSelector(final ServerConnectionSelector delegate, final ListenerStatistics statistics)
    {
        this.delegate = delegate;
        this.statistics = statistics;
    }

    @Override
    public int bytesToIdentifyConnection()
    {
        return this.delegate.bytesToIdentifyConnection();
    }

    @Override
    public Support supports(final BufferData data)
    {
        return this.delegate.supports(data);
    }

    @Override
    public Set<String> supportedApplicationProtocols()
    {
        return this.delegate.supportedApplicationProtocols();
    }

    @Override
    public ServerConnection connection(final ConnectionContext ctx)
    {
        return new Counted(this.delegate.connection(ctx), this.statistics);
    }

    /**
     * Also an {@link InterruptableTask}, as Helidon's HTTP/1 connection is: graceful shutdown
     * interrupts only connections that say they are between requests, and asks by that type.
     */
    private record Counted(ServerConnection delegate, ListenerStatistics statistics) implements ServerConnection, InterruptableTask<Void>
    {
        @Override
        public void handle(final Limit limit) throws InterruptedException
        {
            this.statistics.connectionOpened();
            try
            {
                this.delegate.handle(limit);
            }
            finally
            {
                this.statistics.connectionClosed();
            }
        }

        @Override
        public Duration idleTime()
        {
            return this.delegate.idleTime();
        }

        @Override
        public void close(final boolean interrupt)
        {
            this.delegate.close(interrupt);
        }

        @Override
        public boolean canInterrupt()
        {
            return this.delegate instanceof InterruptableTask<?> task && task.canInterrupt();
        }
    }
}
