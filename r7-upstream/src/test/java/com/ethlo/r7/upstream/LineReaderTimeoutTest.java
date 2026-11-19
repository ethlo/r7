package com.ethlo.r7.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * The read timeout's hand-off between a reading thread and the deadline sweeper. The sweeper
 * closes the socket after claiming a read; a read it claimed must fail even when its bytes win
 * the race, or the connection goes back to the pool and the close lands on the next request.
 */
class LineReaderTimeoutTest
{
    private static final long LONG_AGO = TimeUnit.HOURS.toNanos(1);

    @Test
    void aClaimedReadFailsEvenWhenItsBytesArriveFirst() throws Exception
    {
        final GatedStream in = new GatedStream("HTTP/1.1 200 OK\r\n");
        final HttpUpstream.LineReader reader = new HttpUpstream.LineReader(in);
        final CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> readLine(reader));
        assertThat(in.entered.await(5, TimeUnit.SECONDS)).isTrue();

        // The sweeper sees the read waiting past its timeout and claims it; only then do the
        // bytes come, before the sweeper got to close the socket.
        assertThat(reader.claimTimeout(System.nanoTime() + LONG_AGO, 1)).isTrue();
        in.release.countDown();

        assertThatThrownBy(() -> line.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().cause().isInstanceOf(SocketTimeoutException.class).hasMessage("Read timed out");
    }

    @Test
    void aReadThatCompletedCannotBeClaimed() throws Exception
    {
        final HttpUpstream.LineReader reader = new HttpUpstream.LineReader(
                new ByteArrayInputStream("HTTP/1.1 200 OK\r\nServer: x\r\n".getBytes(StandardCharsets.ISO_8859_1)));
        assertThat(reader.readLine(1024)).isEqualTo("HTTP/1.1 200 OK");
        // Nothing is waiting on the socket: a sweep now must leave the connection alone.
        assertThat(reader.claimTimeout(System.nanoTime() + LONG_AGO, 1)).isFalse();
        assertThat(reader.readLine(1024)).isEqualTo("Server: x");
    }

    @Test
    void aReadWithinItsTimeoutIsNotClaimed() throws Exception
    {
        final GatedStream in = new GatedStream("HTTP/1.1 200 OK\r\n");
        final HttpUpstream.LineReader reader = new HttpUpstream.LineReader(in);
        final CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> readLine(reader));
        assertThat(in.entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(reader.claimTimeout(System.nanoTime(), LONG_AGO)).isFalse();
        in.release.countDown();
        assertThat(line.get(5, TimeUnit.SECONDS)).isEqualTo("HTTP/1.1 200 OK");
    }

    private static String readLine(final HttpUpstream.LineReader reader)
    {
        try
        {
            return reader.readLine(1024);
        }
        catch (final IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Holds the first read until released, then answers it with the given bytes.
     */
    private static final class GatedStream extends InputStream
    {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final byte[] bytes;
        private boolean sent;

        GatedStream(final String text)
        {
            this.bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        }

        @Override
        public int read()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read(final byte[] target, final int offset, final int length) throws IOException
        {
            if (this.sent)
            {
                return -1;
            }
            this.entered.countDown();
            try
            {
                if (!this.release.await(5, TimeUnit.SECONDS))
                {
                    throw new IOException("never released");
                }
            }
            catch (final InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            this.sent = true;
            final int n = Math.min(length, this.bytes.length);
            System.arraycopy(this.bytes, 0, target, offset, n);
            return n;
        }
    }
}
