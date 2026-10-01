package com.ethlo.r7;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.R7Tailer;
import com.ethlo.r7.r7f.R7fConstants;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.r7f.R7fJournalProvider;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * Once every shard is reading its active segment, ticks skip the directory listing and read
 * those segments through mappings kept from earlier ticks. Rotation must still be seen at
 * once: the writer stamps the seal magic before it renames the segment, and a tick that finds
 * it lists the directory again.
 */
class TailerSteadyStateTest
{
    private static final int SEGMENT_SIZE = 64 * 1024;

    @TempDir
    Path journalDir;

    @Test
    void rotationWhileSteadyIsFollowedWithoutLossOrDelay() throws IOException
    {
        final CollectingSink sink = new CollectingSink();
        final R7Tailer tailer = new R7Tailer(journalDir, sink, sink, ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1)));
        final List<String> written = new ArrayList<>();

        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, SEGMENT_SIZE, false)))
        {
            int id = 0;
            while (sealedSegments() < 5)
            {
                final String reqId = "req-" + id++;
                write(journal, reqId);
                written.add(reqId);
                tailer.runTick();
                assertThat(sink.deliveries)
                        .as("each exchange is delivered by the tick right after it is written, across rotations")
                        .endsWith(reqId);
            }
        }
        tailer.runTick();

        assertThat(sink.deliveries).containsExactlyElementsOf(written);
        assertThat(sink.orphanedEnds).isEmpty();
        assertThat(sink.missingEntries).isZero();
    }

    private long sealedSegments() throws IOException
    {
        try (Stream<Path> s = Files.list(journalDir))
        {
            return s.filter(p -> p.getFileName().toString().endsWith(R7fConstants.R7F_FILE_EXTENSION)).count();
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
                BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);
    }
}
