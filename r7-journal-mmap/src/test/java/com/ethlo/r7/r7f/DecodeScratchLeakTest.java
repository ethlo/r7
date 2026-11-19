package com.ethlo.r7.r7f;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalIntegrityListener;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * zstd-jni frees a decompression context only on {@code close()}: there is no cleaner behind
 * it. The tailer decodes on every commit signal, so a context per decode grew native memory
 * until the container was killed. These count the contexts alive across many decodes of
 * compressed segments.
 */
class DecodeScratchLeakTest
{
    private static final int SEGMENT_SIZE = 64 * 1024;
    private static final int COMPRESSION_LEVEL = 1;

    @TempDir
    Path journalDir;

    @Test
    void aTailerKeepsOneContextForAllItsTicksAndFreesItOnShutdown() throws IOException
    {
        final int before = DecodeScratch.openContexts();
        final AtomicLong delivered = new AtomicLong();
        final ExchangeCompletionListener sink = exchange -> delivered.incrementAndGet();
        final R7Tailer tailer = new R7Tailer(journalDir, sink, JournalIntegrityListener.NOOP,
                ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1)));

        final int exchanges = 2_000;
        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, SEGMENT_SIZE, false, COMPRESSION_LEVEL)))
        {
            for (int i = 0; i < exchanges; i++)
            {
                write(journal, "req-" + i);
                tailer.runTick();
                assertThat(DecodeScratch.openContexts() - before)
                        .as("contexts alive after tick %d", i)
                        .isLessThanOrEqualTo(1);
            }
        }
        tailer.runTick();

        assertThat(delivered.get()).isEqualTo(exchanges);
        assertThat(DecodeScratch.openContexts() - before)
                .as("the compressed segments were inflated through the tailer's one context")
                .isEqualTo(1);

        tailer.shutdown();
        assertThat(DecodeScratch.openContexts()).isEqualTo(before);
    }

    @Test
    void aOneOffDecodeFreesItsContext() throws IOException
    {
        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, SEGMENT_SIZE, false, COMPRESSION_LEVEL)))
        {
            for (int i = 0; i < 200; i++)
            {
                write(journal, "req-" + i);
            }
        }
        final List<Path> sealed = sealedSegments();
        assertThat(sealed).isNotEmpty();

        final JournalEventListener ignore = (JournalEventListener) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{JournalEventListener.class}, (proxy, method, args) -> null);
        final int before = DecodeScratch.openContexts();
        long entries = 0;
        for (int round = 0; round < 100; round++)
        {
            for (final Path segment : sealed)
            {
                entries += JournalDecoder.decode(map(segment), ignore).entries();
            }
        }
        assertThat(entries).isPositive();
        assertThat(DecodeScratch.openContexts()).isEqualTo(before);
    }

    private List<Path> sealedSegments() throws IOException
    {
        try (Stream<Path> s = Files.list(journalDir))
        {
            return s.filter(p -> p.getFileName().toString().endsWith(R7fConstants.R7F_FILE_EXTENSION)).sorted().toList();
        }
    }

    private static ByteBuffer map(final Path file) throws IOException
    {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ))
        {
            final MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            final long dataEnd = buffer.getLong(R7fConstants.PREAMBLE_OFF_DATA_END);
            if (buffer.getInt(R7fConstants.PREAMBLE_OFF_SEAL_MAGIC) == R7fConstants.SEAL_MAGIC
                    && dataEnd >= R7fConstants.PREAMBLE_SIZE && dataEnd <= channel.size())
            {
                buffer.limit((int) dataEnd);
            }
            return buffer;
        }
    }

    private static void write(final R7fJournal journal, final String reqId) throws IOException
    {
        journal.clientRequest(JournalLevel.FULL, reqId,
                ByteBuffer.wrap(("GET /" + reqId + " HTTP/1.1").getBytes(StandardCharsets.ISO_8859_1)),
                new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        journal.requestBody(reqId, ByteBuffer.wrap(new byte[2_000]));
        journal.endExchange(reqId, new FastGatewayAttributes(),
                1L, 2L, 200, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED, false, false);
    }
}
