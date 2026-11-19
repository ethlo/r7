package com.ethlo.r7;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GatewayScheduler
{
    private static final Logger log = LoggerFactory.getLogger(GatewayScheduler.class);

    private final ScheduledExecutorService executor;

    public GatewayScheduler(final int poolSize)
    {
        final AtomicLong counter = new AtomicLong(0);
        this.executor = Executors.newScheduledThreadPool(poolSize, runnable ->
                {
                    final Thread thread = new Thread(runnable);
                    thread.setName("r7-scheduler-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
        );
    }

    public ScheduledFuture<?> scheduleEvery(final Duration period, final Runnable task)
    {
        return scheduleEvery(period, period, task);
    }

    /**
     * @param initialDelay until the first run; zero runs it as soon as a scheduler thread is free
     */
    public ScheduledFuture<?> scheduleEvery(final Duration initialDelay, final Duration period, final Runnable task)
    {
        return this.executor.scheduleAtFixedRate(() ->
                {
                    try
                    {
                        task.run();
                    }
                    catch (final Exception e)
                    {
                        log.error("Uncaught exception in scheduled task. Catching to prevent schedule termination.", e);
                    }
                }, initialDelay.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS
        );
    }

    public void shutdown()
    {
        log.info("Shutting down gateway scheduler...");
        this.executor.shutdownNow();
        try
        {
            // Waited for, so that a gateway that has been closed has no thread left running
            if (!this.executor.awaitTermination(5, TimeUnit.SECONDS))
            {
                log.warn("Gateway scheduler threads did not stop within 5 s");
            }
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}