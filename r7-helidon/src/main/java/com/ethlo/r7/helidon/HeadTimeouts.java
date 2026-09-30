package com.ethlo.r7.helidon;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.helidon.http.Headers;
import io.helidon.http.HttpPrologue;
import io.helidon.webserver.ConnectionContext;
import io.helidon.webserver.http1.Http1ConnectionListener;

/**
 * {@code request_parse_timeout} for Helidon, which has none: a connection that has started a
 * request head and not finished it within the timeout is closed.
 * <p>
 * Helidon reads through a {@code SocketChannel}, which ignores {@code SO_TIMEOUT}, and its idle
 * sweep does not count a connection whose request line has arrived as idle. So a client that opens connections and
 * trickles a byte of head now and then held each one - a file descriptor the data plane and the
 * management port share - for as long as it liked. Undertow bounds this with
 * {@code REQUEST_PARSE_TIMEOUT}.
 * <p>
 * Registered as a receive listener on Helidon's HTTP/1 connections, which Helidon calls when a
 * request line has been read and when the head is complete - not per read. The clock runs
 * between the two, which is where slowloris-style clients stall: trickling header lines. A
 * trickled request line never reaches either callback, but Helidon counts a connection still
 * reading its request line as idle, so its own idle timeout ends that one. One daemon thread
 * sweeps for connections past their deadline, as the upstream client does for its own.
 * <p>
 * Helidon 27 does not hand out a connection's socket ({@code ConnectionContext.serverSocket()}
 * throws), so a stalled connection is ended the way NIO allows from another thread: its reader
 * is interrupted, and an interrupted read on an interruptible channel closes the channel. The
 * receive callbacks run on the connection's own virtual thread, which is how it is known. The
 * interrupt must never reach that thread once the head has completed - it would close whatever
 * the request is using, such as its upstream socket - so the sweeper and the head's completion
 * decide under the connection's lock which of them won.
 */
final class HeadTimeouts implements Http1ConnectionListener
{
    private static final Logger logger = LoggerFactory.getLogger(HeadTimeouts.class);
    private static final long SWEEP_MILLIS = 250;

    private final long timeoutNanos;
    private final Map<ConnectionContext, State> connections = new ConcurrentHashMap<>();
    private final Thread sweeper;
    private volatile boolean stopped;

    private static final class State
    {
        /**
         * The connection's reader: the virtual thread its receive callbacks run on.
         */
        final Thread reader = Thread.currentThread();
        /**
         * When the current request line was read; 0 when no head is being read; -1 once the
         * sweeper has ended the connection.
         */
        volatile long headStarted;
    }

    HeadTimeouts(final String name, final Duration timeout)
    {
        this.timeoutNanos = timeout.toNanos();
        this.sweeper = Thread.ofPlatform().daemon().name("r7-head-timeouts-" + name).start(this::sweep);
    }

    @Override
    public void prologue(final ConnectionContext ctx, final HttpPrologue prologue)
    {
        this.connections.computeIfAbsent(ctx, c -> new State()).headStarted = System.nanoTime();
    }

    @Override
    public void headers(final ConnectionContext ctx, final Headers headers)
    {
        final State state = this.connections.get(ctx);
        if (state != null)
        {
            synchronized (state)
            {
                if (state.headStarted == -1)
                {
                    // The sweeper interrupted this thread after its last read returned: the head
                    // made it after all, so the interrupt must not reach what runs next.
                    Thread.interrupted();
                }
                state.headStarted = 0;
            }
        }
    }

    private void sweep()
    {
        while (!this.stopped)
        {
            try
            {
                Thread.sleep(SWEEP_MILLIS);
                final long now = System.nanoTime();
                for (final Map.Entry<ConnectionContext, State> entry : this.connections.entrySet())
                {
                    final State state = entry.getValue();
                    if (!state.reader.isAlive())
                    {
                        // The connection's thread ended with it.
                        this.connections.remove(entry.getKey());
                        continue;
                    }
                    final long started = state.headStarted;
                    if (started > 0 && now - started > this.timeoutNanos)
                    {
                        synchronized (state)
                        {
                            if (state.headStarted == started)
                            {
                                logger.debug("Closing connection {}: request head not complete within {} ms", entry.getKey().childSocketId(), this.timeoutNanos / 1_000_000);
                                state.headStarted = -1;
                                state.reader.interrupt();
                                this.connections.remove(entry.getKey());
                            }
                        }
                    }
                }
            }
            catch (final InterruptedException e)
            {
                return;
            }
            catch (final RuntimeException e)
            {
                logger.warn("Request head timeout sweep failed", e);
            }
        }
    }

    void stop()
    {
        this.stopped = true;
        this.sweeper.interrupt();
    }
}
