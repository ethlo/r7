package com.ethlo.r7;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.R7Tailer;
import com.ethlo.r7.r7f.R7fConstants;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.r7f.R7fJournalProvider;
import com.ethlo.r7.r7f.R7fRecoveryManager;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * What the reader does with a journal that is not intact.
 * <p>
 * The requirement is not that damaged data is recovered — it cannot be. It is that damage
 * is survivable and <em>visible</em>: the reader keeps going, and every lost or skipped
 * region is reported. A reader that quietly returns fewer records than were written is
 * worse than one that fails loudly.
 * <p>
 * These tests encode the on-disk framing deliberately, so a change to the format has to
 * be made here too rather than silently invalidating the corpus.
 */
class JournalIntegrityTest
{
    private static final int SEGMENT_SIZE = 256 * 1024;

    @TempDir
    Path journalDir;

    /**
     * A flipped bit inside one entry must cost exactly that entry. Everything before and
     * after it still decodes, and the damage is reported.
     */
    @Test
    void corruptEntryIsSkippedAndReported() throws IOException
    {
        writeExchanges(6);
        final Path segment = onlySealedSegment();

        final List<EntryRef> entries = entriesOf(segment);
        assertThat(entries).hasSizeGreaterThan(6);

        // Corrupt the payload of an entry in the middle, leaving the framing intact so the
        // CRC is what catches it.
        final EntryRef victim = entries.get(entries.size() / 2);
        flipByte(segment, victim.payloadOffset() + 1);

        final CollectingSink sink = tail();

        assertThat(sink.corruptRegions).as("damage must be reported").isNotEmpty();
        assertThat(sink.completed).as("entries after the damage must still decode").isNotEmpty();
        assertThat(sink.completed.size() + sink.incompleteEnds.size() + sink.orphanedBodies.size())
                .as("the reader kept going past the corrupt entry")
                .isGreaterThan(1);
    }

    /**
     * An entry overwritten wholesale, rather than subtly corrupted, leaves a hole. The
     * reader must resynchronise on the next entry and then notice, from the sequence
     * numbers, that something is missing — the case that is invisible without them.
     */
    @Test
    void holeInTheMiddleIsDetectedAsMissingEntries() throws IOException
    {
        writeExchanges(8);
        final Path segment = onlySealedSegment();

        final List<EntryRef> entries = entriesOf(segment);
        final EntryRef victim = entries.get(entries.size() / 2);

        // 0xFF rather than 0x00: zeros mean "never written" and legitimately stop the scan.
        final byte[] rubbish = new byte[victim.totalLength()];
        java.util.Arrays.fill(rubbish, (byte) 0xFF);
        overwrite(segment, victim.offset(), rubbish);

        final CollectingSink sink = tail();

        assertThat(sink.missingEntries)
                .as("the sequence numbers are what make this detectable; sink: %s", sink)
                .isGreaterThan(0);
        assertThat(sink.corruptRegions).isNotEmpty();
    }

    /**
     * A segment cut mid-entry — the normal shape of an unclean stop — must not throw, and
     * must not silently claim the truncated entry decoded.
     */
    @Test
    void truncatedTailStopsCleanly() throws IOException
    {
        writeExchanges(6);
        final Path segment = onlySealedSegment();

        final List<EntryRef> entries = entriesOf(segment);
        final EntryRef last = entries.get(entries.size() - 1);

        // Cut halfway through the final entry.
        try (var channel = java.nio.channels.FileChannel.open(segment, StandardOpenOption.WRITE))
        {
            channel.truncate(last.offset() + (last.totalLength() / 2));
        }

        final CollectingSink sink = tail();

        // Whatever the reader reports, it must not have invented the truncated entry and
        // must not have thrown.
        assertThat(sink.completed.size()).isLessThanOrEqualTo(6);
        assertThat(sink.sequenceRegressions).isEmpty();
    }

    /**
     * A file that is not an r7f segment at all must be set aside, not deleted and not
     * rescanned on every boot.
     */
    @Test
    void foreignFileIsQuarantined() throws IOException
    {
        final Path bogus = journalDir.resolve("shard-0-1700000000000-1" + R7fConstants.ACTIVE_FILE_EXTENSION);
        final byte[] content = new byte[R7fConstants.PREAMBLE_SIZE + 64];
        java.util.Arrays.fill(content, (byte) 'Z');
        Files.write(bogus, content);

        final CollectingSink sink = new CollectingSink();
        R7fRecoveryManager.cleanAndRecover(journalDir, sink);

        assertThat(sink.quarantined).hasSize(1);
        assertThat(Files.exists(bogus)).as("the original must not be left in place").isFalse();
        assertThat(quarantinedFiles()).hasSize(1);
    }

    /**
     * A file shorter than the preamble used to throw inside recovery and be left behind
     * to be rescanned forever.
     */
    @Test
    void truncatedPreambleIsQuarantined() throws IOException
    {
        final Path stub = journalDir.resolve("shard-0-1700000000000-2" + R7fConstants.ACTIVE_FILE_EXTENSION);
        Files.write(stub, new byte[32]);

        final CollectingSink sink = new CollectingSink();
        R7fRecoveryManager.cleanAndRecover(journalDir, sink);

        assertThat(sink.quarantined).hasSize(1);
        assertThat(quarantinedFiles()).hasSize(1);
    }

    /**
     * Body bytes that do not match the checksum the gateway recorded mean the stored
     * record is not what crossed the wire. That must be reported, not quietly served.
     */
    @Test
    void bodyChecksumMismatchIsReported() throws IOException
    {
        final String reqId = "req-checksum";
        final byte[] body = "the original body".getBytes(StandardCharsets.ISO_8859_1);

        // Deliberately record a checksum that does not describe the body being written,
        // standing in for a body that was altered between the wire and the journal.
        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, SEGMENT_SIZE, true)))
        {
            journal.clientRequest(JournalLevel.FULL, reqId, ByteBuffer.wrap("GET / HTTP/1.1".getBytes(StandardCharsets.ISO_8859_1)),
                    new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
            journal.requestBody(reqId, ByteBuffer.wrap(body));
            journal.endExchange(reqId, new FastGatewayAttributes(),
                    1L, 2L, 200, 0L, body.length, 0L, 0L, 0L, 0L, 0L,
                    0x0BADC0DE, 0);
        }

        final CollectingSink sink = tail();

        assertThat(sink.checksumMismatches).containsExactly(reqId + ":REQUEST");
    }

    /* ---------- journal writing ---------- */

    private void writeExchanges(final int count) throws IOException
    {
        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, SEGMENT_SIZE, true)))
        {
            for (int i = 0; i < count; i++)
            {
                final String reqId = "req-" + i;
                journal.clientRequest(JournalLevel.FULL, reqId,
                        ByteBuffer.wrap(("GET /item/" + i + " HTTP/1.1").getBytes(StandardCharsets.ISO_8859_1)),
                        new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
                journal.requestBody(reqId, ByteBuffer.wrap(("body-" + i).getBytes(StandardCharsets.ISO_8859_1)));
                journal.endExchange(reqId, new FastGatewayAttributes(),
                        1L, 2L, 200, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0, 0);
            }
        }
    }

    private CollectingSink tail() throws IOException
    {
        final CollectingSink sink = new CollectingSink();
        new R7Tailer(journalDir, Duration.ofHours(1), sink, sink,
                ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1))).runTick();
        return sink;
    }

    /* ---------- on-disk framing, encoded on purpose ---------- */

    /**
     * Entry framing, format version 1:
     * {@code magic(4) sequence(4) payloadLen(4) fbLen(4) rawLen(4) payload crc(4)}.
     */
    private record EntryRef(int offset, int sequence, int fbLen, int rawLen)
    {
        int payloadOffset()
        {
            return offset + R7fConstants.ENTRY_HEADER_SIZE;
        }

        int totalLength()
        {
            return R7fConstants.ENTRY_HEADER_SIZE + fbLen + rawLen + Integer.BYTES;
        }
    }

    private static List<EntryRef> entriesOf(final Path segment) throws IOException
    {
        final ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(segment)).order(ByteOrder.BIG_ENDIAN);
        final List<EntryRef> entries = new ArrayList<>();

        int pos = R7fConstants.PREAMBLE_SIZE;
        while (pos + R7fConstants.MIN_ENTRY_SIZE <= buffer.limit())
        {
            if (buffer.getInt(pos) != R7fConstants.MAGIC)
            {
                break;
            }
            final int sequence = buffer.getInt(pos + 4);
            final int fbLen = buffer.getInt(pos + 12);
            final int rawLen = buffer.getInt(pos + 16);
            final EntryRef entry = new EntryRef(pos, sequence, fbLen, rawLen);
            entries.add(entry);
            pos += entry.totalLength();
        }
        return entries;
    }

    private static void flipByte(final Path file, final int offset) throws IOException
    {
        final byte[] bytes = Files.readAllBytes(file);
        bytes[offset] ^= (byte) 0xFF;
        Files.write(file, bytes);
    }

    private static void overwrite(final Path file, final int offset, final byte[] replacement) throws IOException
    {
        final byte[] bytes = Files.readAllBytes(file);
        System.arraycopy(replacement, 0, bytes, offset, Math.min(replacement.length, bytes.length - offset));
        Files.write(file, bytes);
    }

    private Path onlySealedSegment() throws IOException
    {
        final List<Path> sealed = filesEndingWith(R7fConstants.R7F_FILE_EXTENSION);
        assertThat(sealed).as("expected exactly one sealed segment").hasSize(1);
        return sealed.get(0);
    }

    private List<Path> quarantinedFiles() throws IOException
    {
        return filesEndingWith(R7fConstants.CORRUPT_FILE_EXTENSION);
    }

    private List<Path> filesEndingWith(final String suffix) throws IOException
    {
        try (Stream<Path> s = Files.list(journalDir))
        {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
        }
    }
}
