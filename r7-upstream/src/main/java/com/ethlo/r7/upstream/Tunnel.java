package com.ethlo.r7.upstream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A connection the upstream switched to another protocol (101, a WebSocket in practice): from
 * here on bytes are copied both ways, uninterpreted, until either side closes. The relay hands it
 * to the server's {@link ProxiedExchange#upgrade}, and the server {@link #run}s it once it has the
 * client connection's raw streams - on Níma right after the 101, on a servlet container only once
 * {@code service()} has returned and the container has upgraded the connection.
 * <p>
 * Either side ending - a close, a reset, a failed write - ends both: a half-closed WebSocket has
 * nothing left to say (RFC 6455 §7.1.1 has the server close the TCP connection first, after the
 * closing handshake both directions have already carried), and a tunnel that waited for the
 * other side to close too would hold two connections and a thread for a peer that never does.
 * <p>
 * The tunnel holds no pool slot and is not bounded by {@code max_request_time}: it is no longer a
 * request. An idle tunnel is ended by the server's own idle timeout, through {@link #idleNanos}
 * and {@link #close}.
 */
public final class Tunnel
{
    private static final int BUFFER_SIZE = 8192;

    /**
     * The client's side of the upgraded connection, as the server hands it over.
     */
    public interface Client
    {
        /**
         * Reads what has arrived, blocking for at least one byte.
         *
         * @return the number of bytes read, at least 1, or -1 when the client closed the connection
         */
        int read(byte[] buffer, int offset, int length) throws IOException;

        /**
         * Writes and flushes.
         */
        void write(byte[] buffer, int offset, int length) throws IOException;

        /**
         * Closes the client connection. It must end a read or write blocked in another thread:
         * that is how the tunnel stops the direction that did not end on its own.
         */
        void close();
    }

    private final HttpUpstream.Connection upstream;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Client client;
    private volatile long lastActivity = System.nanoTime();

    Tunnel(final HttpUpstream.Connection upstream)
    {
        this.upstream = upstream;
    }

    /**
     * Copies both ways until either side ends, then closes both. Blocks until both directions
     * have stopped: the client-to-upstream direction runs on the calling thread, the other on a
     * virtual thread of its own. Called at most once; after {@link #close} it only closes the
     * client.
     */
    public void run(final Client client)
    {
        this.client = client;
        if (this.closed.get())
        {
            // Closed before the server got here: the server is stopping, or gave up on the switch.
            client.close();
            return;
        }
        try
        {
            // The read timeout bounded the wait for a response; a WebSocket may be quiet for long.
            this.upstream.socket.setSoTimeout(0);
        }
        catch (final SocketException e)
        {
            close();
            return;
        }
        final Thread downstream = Thread.ofVirtual().name("r7-tunnel").start(this::upstreamToClient);
        try
        {
            clientToUpstream(client);
        }
        finally
        {
            close();
            // A write to a client that stopped reading is not ended by closing the upstream; on a
            // server whose client connection is an interruptible channel this closes it as well.
            downstream.interrupt();
            joinUninterruptibly(downstream);
            // Client.close may have ended this thread's read by interrupting it; the interrupt was
            // ours and is spent, and must not reach the server's code after the tunnel.
            Thread.interrupted();
        }
    }

    /**
     * Ends the tunnel, or, before {@link #run}, releases the upstream connection the server will
     * not use. Idempotent, and safe from any thread.
     */
    public void close()
    {
        if (this.closed.compareAndSet(false, true))
        {
            this.upstream.close();
            final Client current = this.client;
            if (current != null)
            {
                current.close();
            }
        }
    }

    /**
     * How long since a byte last went through in either direction, for the server's idle timeout.
     */
    public long idleNanos()
    {
        return System.nanoTime() - this.lastActivity;
    }

    private void clientToUpstream(final Client client)
    {
        final byte[] buffer = new byte[BUFFER_SIZE];
        try
        {
            int n;
            while ((n = client.read(buffer, 0, buffer.length)) != -1)
            {
                this.upstream.out.write(buffer, 0, n);
                this.upstream.out.flush();
                this.lastActivity = System.nanoTime();
            }
        }
        catch (final IOException | UncheckedIOException e)
        {
            // Either side went away, or close() ended the read: the tunnel is over either way.
        }
    }

    private void upstreamToClient()
    {
        final byte[] buffer = new byte[BUFFER_SIZE];
        try
        {
            int n;
            // Through the line reader, not the socket: the upstream may have sent its first frames
            // right behind the 101, and they are in the reader's buffer.
            while ((n = this.upstream.reader.read(buffer, 0, buffer.length)) != -1)
            {
                this.client.write(buffer, 0, n);
                this.lastActivity = System.nanoTime();
            }
        }
        catch (final IOException | UncheckedIOException e)
        {
            // As above.
        }
        finally
        {
            close();
        }
    }

    private static void joinUninterruptibly(final Thread thread)
    {
        while (true)
        {
            try
            {
                thread.join();
                return;
            }
            catch (final InterruptedException e)
            {
                // Client.close interrupts the reading thread - this one - to end its read; the
                // other direction is already being closed, so keep waiting for it.
            }
        }
    }
}
