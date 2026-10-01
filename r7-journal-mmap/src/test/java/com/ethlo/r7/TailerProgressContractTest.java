package com.ethlo.r7;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.JournalIntegrityListener;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.journal.api.TailerProgress;
import com.ethlo.r7.r7f.R7Tailer;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.r7f.R7fJournalProvider;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * What R7Tailer writes is what a reaper reads: the checkpoint file is the contract between
 * them, and the two sides must agree on it, or a reaper deletes a segment a tailer still needs.
 */
class TailerProgressContractTest
{
    @TempDir
    Path dir;

    @Test
    void aFinishedSegmentReadsAsDoneAndOneHoldingAnOpenExchangeDoesNot() throws IOException
    {
        final Path journals = Files.createDirectory(dir.resolve("journals"));
        final Path checkpoints = dir.resolve("checkpoints");

        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journals, 0, 256 * 1024, false)))
        {
            write(journal, "done", true);
        }
        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journals, 0, 256 * 1024, false)))
        {
            write(journal, "open", false);
        }

        new R7Tailer(journals, checkpoints, (exchange) ->
        {
        }, JournalIntegrityListener.NOOP, ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1))).runTick();

        final TailerProgress progress = TailerProgress.read(checkpoints);
        assertThat(progress).isNotNull();
        assertThat(progress.isDoneWith(0, 1)).as("read to the end, everything delivered").isTrue();
        assertThat(progress.isDoneWith(0, 2)).as("holds the start of an exchange still open").isFalse();
        assertThat(progress.isDoneWith(0, 3)).as("never seen").isFalse();
    }

    private static void write(final R7fJournal journal, final String reqId, final boolean end) throws IOException
    {
        journal.clientRequest(JournalLevel.FULL, reqId,
                ByteBuffer.wrap(("GET /" + reqId + " HTTP/1.1").getBytes(StandardCharsets.ISO_8859_1)),
                new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        if (end)
        {
            journal.endExchange(reqId, new FastGatewayAttributes(),
                    1L, 2L, 200, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                    BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);
        }
    }
}
