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
import java.util.HashSet;
import java.util.List;
import java.util.zip.CRC32C;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalIntegrityListener;
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

    /** Mirrors {@code R7Tailer.CHECKPOINT_FILE}, which is private to the tailer. */
    private static final String CHECKPOINT_FILE = ".r7_checkpoints";

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
                    BodyChecksum.ofUnsigned32(0x0BADC0DE), BodyChecksum.NOT_RECORDED);
        }

        final CollectingSink sink = tail();

        assertThat(sink.checksumMismatches).containsExactly(reqId + ":REQUEST");
    }

    /**
     * The realistic power-loss hole: a page that never reached the device reads back as
     * zeroes, because the segment was pre-allocated zero-filled.
     * <p>
     * This is the case that made the sequence numbers worth adding, and the case a reader
     * that stops at the first zero byte cannot see — it looks exactly like the unwritten
     * tail. A sealed segment declares where its data ends, so zeroes before that are a
     * hole by definition and the reader must look past them.
     */
    @Test
    void holeOfZeroesInSealedSegmentIsDetected() throws IOException
    {
        writeExchanges(8);
        final Path segment = onlySealedSegment();

        final List<EntryRef> entries = entriesOf(segment);
        final EntryRef victim = entries.get(entries.size() / 2);

        overwrite(segment, victim.offset(), new byte[victim.totalLength()]);

        final CollectingSink sink = tail();

        assertThat(sink.missingEntries)
                .as("zeroes in a sealed segment are a hole, not an end; sink: %s", sink)
                .isGreaterThan(0);
        assertThat(sink.completed)
                .as("entries after the hole must still be read")
                .isNotEmpty();
    }

    /**
     * A backward sequence means the file is not a valid append-only segment. Continuing
     * would replay duplicate or out-of-order events into the reassembler and build
     * exchanges that never happened, so FORMAT.md §6 requires the reader to stop.
     */
    @Test
    void sequenceRegressionStopsDecoding() throws IOException
    {
        writeExchanges(9);
        final Path segment = onlySealedSegment();

        final List<EntryRef> entries = entriesOf(segment);
        final int victimIndex = entries.size() / 2;
        rewriteSequence(segment, entries.get(victimIndex), 1);

        final CollectingSink sink = tail();

        assertThat(sink.sequenceRegressions).as("sink: %s", sink).isNotEmpty();
        assertThat(sink.completed.size())
                .as("decoding must stop at the regression, not carry on")
                .isLessThan(9);
    }

    /**
     * Only active files pass through recovery. A sealed or compressed file that is not a
     * supported segment must not be decoded as whatever its bytes resemble and then
     * deleted as processed.
     */
    @Test
    void unsupportedVersionIsQuarantinedByTheTailer() throws IOException
    {
        final Path sealed = journalDir.resolve("shard-0-1700000000000-1-1-2" + R7fConstants.R7F_FILE_EXTENSION);
        final ByteBuffer content = ByteBuffer.allocate(R7fConstants.PREAMBLE_SIZE + 64).order(ByteOrder.BIG_ENDIAN);
        content.putInt(R7fConstants.PREAMBLE_OFF_MAGIC, R7fConstants.MAGIC);
        content.putShort(R7fConstants.PREAMBLE_OFF_VERSION, (short) 99);
        Files.write(sealed, content.array());

        final CollectingSink sink = tail();

        assertThat(sink.quarantined).as("sink: %s", sink).hasSize(1);
        assertThat(Files.exists(sealed)).as("must not be left in place to be rescanned").isFalse();
        assertThat(quarantinedFiles()).hasSize(1);
    }

    /**
     * An active segment belongs to the writer. The warmer pre-allocates the next one with
     * an all-zero header, and the writer stamps its preamble only when it claims it, so
     * the tailer must leave an unrecognised active file alone rather than rename it out
     * from under a live mapping.
     */
    @Test
    void activeSegmentsAreNeverQuarantinedByTheTailer() throws IOException
    {
        final Path spare = journalDir.resolve("shard-0-1700000000000-1" + R7fConstants.ACTIVE_FILE_EXTENSION);
        Files.write(spare, new byte[R7fConstants.PREAMBLE_SIZE + 64]);

        final CollectingSink sink = tail();

        assertThat(sink.quarantined).as("sink: %s", sink).isEmpty();
        assertThat(Files.exists(spare)).as("the writer's file must be left where it is").isTrue();
        assertThat(quarantinedFiles()).isEmpty();
    }

    /**
     * The tailer keys segments by shard and sequence, so the sequence has to keep
     * increasing across a restart. A counter that began again at zero would give a new
     * segment the same key as a retained one, and the tailer would silently read only one
     * of the two.
     */
    @Test
    void segmentSequencesDoNotCollideAcrossRestart() throws IOException
    {
        for (int run = 0; run < 3; run++)
        {
            try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, SEGMENT_SIZE, true)))
            {
                final String reqId = "restart-" + run;
                journal.clientRequest(JournalLevel.METADATA, reqId,
                        ByteBuffer.wrap("GET / HTTP/1.1".getBytes(StandardCharsets.ISO_8859_1)),
                        new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
                journal.endExchange(reqId, new FastGatewayAttributes(),
                        1L, 2L, 200, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                        BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);
            }
        }

        final List<String> keys = new ArrayList<>();
        try (Stream<Path> files = Files.list(journalDir))
        {
            for (final Path p : files.toList())
            {
                final String[] parts = p.getFileName().toString().split("-");
                if (parts.length >= 4 && parts[0].equals("shard"))
                {
                    keys.add(parts[1] + "-" + parts[3].split("\\.")[0]);
                }
            }
        }

        assertThat(keys).as("every segment needs its own shard+sequence key").isNotEmpty();
        assertThat(new HashSet<>(keys))
                .as("duplicate keys mean the tailer would drop a segment: %s", keys)
                .hasSameSizeAs(keys);
    }

    /**
     * A hole far larger than one page. The reader used to abandon the rest of the file
     * after a megabyte of scanning, which contradicted FORMAT.md §6 and discarded entries
     * whose framing and CRC were perfectly intact.
     */
    @Test
    void largeHoleIsCrossedAndReported() throws IOException
    {
        writeExchanges(200);
        final Path segment = onlySealedSegment();

        final List<EntryRef> entries = entriesOf(segment);
        final EntryRef from = entries.get(entries.size() / 3);
        final long available = Files.size(segment) - from.offset();
        final int holeBytes = (int) Math.min(16 * 1024L, available / 2);

        overwrite(segment, from.offset(), new byte[holeBytes]);

        final CollectingSink sink = tail();

        assertThat(sink.missingEntries).as("sink: %s", sink).isGreaterThan(0);
        assertThat(sink.completed).as("entries before the hole").containsKey("req-0");
        assertThat(sink.completed).as("entries after the hole").containsKey("req-199");
    }

    /**
     * If every body entry for an exchange is lost, there is nothing to compare against the
     * checksum the gateway recorded — which is exactly when saying nothing is worst. The
     * exchange would otherwise be emitted as complete with its body silently absent.
     */
    @Test
    void absentBodyWithAJournaledChecksumIsReported() throws IOException
    {
        final String reqId = "req-no-body";

        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, SEGMENT_SIZE, true)))
        {
            journal.clientRequest(JournalLevel.FULL, reqId,
                    ByteBuffer.wrap("POST /upload HTTP/1.1".getBytes(StandardCharsets.ISO_8859_1)),
                    new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
            // No requestBody entry at all, but the end event says a body was seen.
            journal.endExchange(reqId, new FastGatewayAttributes(),
                    1L, 2L, 200, 0L, 4096L, 0L, 0L, 0L, 0L, 0L,
                    BodyChecksum.ofUnsigned32(0x12345678), BodyChecksum.NOT_RECORDED);
        }

        final CollectingSink sink = tail();

        assertThat(sink.checksumMismatches).as("sink: %s", sink).containsExactly(reqId + ":REQUEST");
    }

    /**
     * A journaled checksum of zero must still be verified. CRC32C evaluates to zero for
     * some non-empty inputs, so treating zero as "there was no body" would wave through
     * exactly the exchanges whose bodies hash that way — including ones whose stored body
     * was lost entirely, as here.
     */
    @Test
    void zeroChecksumIsStillVerified() throws IOException
    {
        final String reqId = "req-zero-crc";

        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, SEGMENT_SIZE, true)))
        {
            journal.clientRequest(JournalLevel.FULL, reqId,
                    ByteBuffer.wrap("POST /x HTTP/1.1".getBytes(StandardCharsets.ISO_8859_1)),
                    new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
            // A body was seen on the wire but none was journaled, and the recorded
            // checksum happens to be zero.
            journal.endExchange(reqId, new FastGatewayAttributes(),
                    1L, 2L, 200, 0L, 128L, 0L, 0L, 0L, 0L, 0L,
                    BodyChecksum.ofUnsigned32(0), BodyChecksum.NOT_RECORDED);
        }

        final CollectingSink sink = tail();

        assertThat(sink.checksumMismatches).as("sink: %s", sink).containsExactly(reqId + ":REQUEST");
    }

    /**
     * A file that is not a segment is not the journal's business.
     * <p>
     * Compression is no longer a phase of a segment's life — it is one thing a consumer may
     * do with a sealed segment — so a {@code .zst} sitting in the journal directory belongs
     * to whoever put it there. The tailer must not read it, quarantine it or delete it, and
     * `FORMAT.md` 3.1 says so. This test exists to fail if anything ever puts a non-segment
     * extension back into the tailer's filter.
     */
    @Test
    void aFileThatIsNotASegmentIsLeftAlone() throws IOException
    {
        final Path notASegment = journalDir.resolve(
                "shard-0-1700000000000-1-1-2" + R7fConstants.R7F_FILE_EXTENSION + R7fConstants.COMPRESSED_FILE_EXTENSION);
        final byte[] contents = new byte[512];
        java.util.Arrays.fill(contents, (byte) 'Q');
        Files.write(notASegment, contents);

        final CollectingSink sink = tail();

        assertThat(sink.quarantined).as("sink: %s", sink).isEmpty();
        assertThat(sink.corruptRegions).isEmpty();
        assertThat(Files.exists(notASegment)).as("someone else's file must be left exactly as it was").isTrue();
        assertThat(quarantinedFiles()).isEmpty();
    }

    /**
     * A segment whose preamble page was lost but whose later pages survived looks, from
     * its first six bytes, exactly like a spare the writer never took. Deleting it on that
     * evidence would destroy recoverable records under the very failure mode this format
     * documents.
     */
    @Test
    void segmentWithLostPreambleButSurvivingDataIsQuarantined() throws IOException
    {
        final Path active = journalDir.resolve("shard-0-1700000000000-1" + R7fConstants.ACTIVE_FILE_EXTENSION);
        final byte[] content = new byte[R7fConstants.PREAMBLE_SIZE + 512];
        // Preamble lost to zeroes, data after it intact.
        java.util.Arrays.fill(content, R7fConstants.PREAMBLE_SIZE, content.length, (byte) 'D');
        Files.write(active, content);

        final CollectingSink sink = new CollectingSink();
        R7fRecoveryManager.cleanAndRecover(journalDir, sink);

        assertThat(sink.quarantined).as("sink: %s", sink).hasSize(1);
        assertThat(quarantinedFiles()).hasSize(1);
    }

    /**
     * The companion case: a genuinely untouched pre-allocation is deleted quietly, because
     * every unclean stop leaves one and reporting it would train operators to ignore the
     * integrity signal.
     */
    @Test
    void entirelyEmptyPreAllocationIsDeletedQuietly() throws IOException
    {
        final Path spare = journalDir.resolve("shard-0-1700000000000-2" + R7fConstants.ACTIVE_FILE_EXTENSION);
        Files.write(spare, new byte[R7fConstants.PREAMBLE_SIZE + 512]);

        final CollectingSink sink = new CollectingSink();
        R7fRecoveryManager.cleanAndRecover(journalDir, sink);

        assertThat(sink.quarantined).as("sink: %s", sink).isEmpty();
        assertThat(Files.exists(spare)).isFalse();
        assertThat(quarantinedFiles()).isEmpty();
    }

    /**
     * {@code 0xFFFFFFFF} is an ordinary CRC32C, not a sentinel.
     * <p>
     * While checksums travelled as {@code int}, that value <em>was</em> {@code -1}, so a
     * body hashing to all ones had its verification skipped by the mechanism that exists to
     * guarantee it — about one body in four billion, silently, and only ever on the bodies
     * unlucky enough to hash that way. Carrying them as {@code long} is what puts the
     * sentinel outside the value domain.
     */
    @Test
    void aChecksumOfAllOnesIsNotMistakenForAnAbsentOne() throws IOException
    {
        final String reqId = "req-all-ones";
        final byte[] body = "not the body that hashes to all ones".getBytes(StandardCharsets.ISO_8859_1);

        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, SEGMENT_SIZE, true)))
        {
            journal.clientRequest(JournalLevel.FULL, reqId,
                    ByteBuffer.wrap("POST /x HTTP/1.1".getBytes(StandardCharsets.ISO_8859_1)),
                    new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
            journal.requestBody(reqId, ByteBuffer.wrap(body));
            journal.endExchange(reqId, new FastGatewayAttributes(),
                    1L, 2L, 200, 0L, body.length, 0L, 0L, 0L, 0L, 0L,
                    BodyChecksum.ofUnsigned32(0xFFFFFFFFL), BodyChecksum.NOT_RECORDED);
        }

        final CollectingSink sink = tail();

        assertThat(sink.checksumMismatches)
                .as("a recorded checksum of 0xFFFFFFFF must be verified like any other")
                .containsExactly(reqId + ":REQUEST");
    }

    /**
     * Entries lost from the <em>start</em> of a segment must be reported.
     * <p>
     * Sequences restart at 1 in every segment, so a reader opening a segment it has never
     * seen knows exactly what the first entry must be. Starting with no expectation instead
     * — adopting whichever sequence turns up first — makes a segment that lost its opening
     * entries read back as a shorter but perfectly consistent one. That is the only point
     * in the file where the loss is detectable at all, since there is no earlier entry to
     * compare against.
     */
    @Test
    void entriesLostAtTheStartOfASegmentAreReported() throws IOException
    {
        writeExchanges(1);
        final Path segment = onlySealedSegment();

        final List<EntryRef> entries = entriesOf(segment);
        assertThat(entries).hasSizeGreaterThan(1);
        assertThat(entries.get(0).sequence()).isEqualTo(R7fConstants.FIRST_ENTRY_SEQUENCE);

        // The segment now opens at #5: entries 1 through 4 were written and never reached
        // the device. The framing and CRC stay valid, so the sequence is the only evidence
        // and the reader's expectation is the only thing it can be checked against.
        rewriteSequence(segment, entries.get(0), 5);

        final CollectingSink sink = tail();

        assertThat(sink.missingEntries)
                .as("a fresh segment must expect #%d, so opening at #5 is four entries lost. sink: %s",
                        R7fConstants.FIRST_ENTRY_SEQUENCE, sink)
                .isEqualTo(4L);

        // The entries after it still carry their original numbering, which now steps
        // backwards. That is reported and decoding stops, per FORMAT.md 6 - incidental
        // here, but asserted so the test fails loudly if that behaviour changes.
        assertThat(sink.sequenceRegressions).hasSize(1);
    }

    /**
     * The reader must not consume an active segment's pre-allocated tail when it finds an
     * entry it cannot parse.
     * <p>
     * The writer stamps the magic first and the CRC last, so an entry caught mid-publish is
     * byte-for-byte a corrupt entry: header present, payload short, checksum wrong. The
     * resync scan then finds nothing after it, because everything past the write frontier
     * is still zero. Treating that as "no further entries, stop" and jumping to the end of
     * the buffer checkpoints the whole pre-allocation — and every entry appended afterwards
     * is skipped, silently, for the life of the segment.
     * <p>
     * This is not a rare corruption path. It is what tailing a live writer looks like
     * whenever a tick lands between the magic and the checksum.
     */
    @Test
    void partialTrailingEntryInAnActiveSegmentIsRetriedNotConsumed() throws IOException
    {
        writeExchanges(6);
        final Path sealed = onlySealedSegment();
        final byte[] complete = Files.readAllBytes(sealed);
        final List<EntryRef> entries = entriesOf(sealed);
        final EntryRef last = entries.get(entries.size() - 1);

        // Rebuild it as the writer would have had it a moment earlier: an active segment is
        // the full pre-allocation, and the final entry is fully assembled but not yet
        // published — its magic slot is still zero, because the writer stamps the magic last.
        final byte[] active = new byte[SEGMENT_SIZE];
        System.arraycopy(complete, 0, active, 0, complete.length);
        java.util.Arrays.fill(active, last.offset(), last.offset() + Integer.BYTES, (byte) 0);

        final Path activePath = journalDir.resolve(activeNameFor(sealed));
        Files.delete(sealed);
        Files.write(activePath, active);

        // One tailer across both ticks: the reassembler holds the half-seen exchange, which
        // is exactly how this runs in production.
        final CollectingSink sink = new CollectingSink();
        final R7Tailer tailer = new R7Tailer(journalDir, Duration.ofHours(1), sink, sink,
                ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1)));

        tailer.runTick();
        assertThat(sink.completed)
                .as("the exchange whose final entry is still being written cannot be complete yet")
                .hasSize(5);

        // The writer publishes it: the magic goes in last, and the entry becomes visible.
        System.arraycopy(complete, last.offset(), active, last.offset(), Integer.BYTES);
        Files.write(activePath, active);

        tailer.runTick();
        assertThat(sink.completed)
                .as("the entry that was mid-write on the first tick must be picked up on the second")
                .hasSize(6);
        assertThat(sink.missingEntries).as("nothing was lost, so nothing may be reported missing").isZero();
        assertThat(sink.sequenceRegressions).isEmpty();
    }

    /**
     * Invariant 3 says (shard, sequence) is unique and increasing <em>for ever</em>. Seeding
     * the counter from the segments on disk only delivers that while at least one segment is
     * retained: retention deletes them once they have been read, and a shard drained to
     * empty would otherwise start over at one. The tailer keys its checkpoints by that pair,
     * so a reused sequence can have a brand-new segment resumed at a dead one's offset — and,
     * if that offset is past its data, deleted unread.
     */
    @Test
    void segmentSequenceDoesNotRestartAfterEverySegmentIsDeleted() throws IOException
    {
        writeExchanges(2);
        final long firstSequence = sequenceOfOnlySegment();

        // Retention, having read everything, removes every segment for this shard.
        for (final Path segment : filesEndingWith(R7fConstants.R7F_FILE_EXTENSION))
        {
            Files.delete(segment);
        }
        assertThat(filesEndingWith(R7fConstants.R7F_FILE_EXTENSION)).isEmpty();

        writeExchanges(2);

        assertThat(sequenceOfOnlySegment())
                .as("a restart that finds no segments must still not reuse a sequence number")
                .isGreaterThan(firstSequence);
    }

    /**
     * The other half of the same problem: pruning a checkpoint when its segment is deleted
     * does nothing if emptying the map skips the save entirely and leaves the previous file
     * in place. A restart then loads checkpoints for segments that no longer exist.
     */
    @Test
    void checkpointFileIsRemovedOnceEverySegmentHasBeenReadAndDeleted() throws IOException
    {
        writeExchanges(3);

        final CollectingSink sink = new CollectingSink();
        // No minimum age: a segment is deleted as soon as it has been fully read.
        new R7Tailer(journalDir, null, sink, sink,
                ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1))).runTick();

        assertThat(sink.completed).hasSize(3);
        assertThat(filesEndingWith(R7fConstants.R7F_FILE_EXTENSION))
                .as("a fully read segment is deleted").isEmpty();
        assertThat(Files.exists(journalDir.resolve(CHECKPOINT_FILE)))
                .as("no segments left means no checkpoints to keep")
                .isFalse();
    }

    /**
     * A recorded checksum must be verified even when the rest of the record is damaged.
     * <p>
     * Verification used to also require the start event's journal level and a positive
     * body-byte count, both of which live in <em>other</em> entries. That made a sufficient
     * condition into a conjunction that fails open: lose the client-request entry, or record
     * the wrong byte count, and the check is skipped on exactly the exchange whose record is
     * already in doubt. Here the request body was journaled and its checksum recorded, but
     * there is no client-request entry and the traffic counter says zero.
     */
    @Test
    void aRecordedChecksumIsVerifiedEvenWithoutTheStartEvent() throws IOException
    {
        final String reqId = "req-no-start";
        final byte[] body = "the stored body".getBytes(StandardCharsets.ISO_8859_1);

        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, SEGMENT_SIZE, true)))
        {
            // A client response, so the exchange exists, but no client request: its journal
            // level is never learned.
            journal.clientResponse(JournalLevel.FULL, reqId, 200,
                    ByteBuffer.wrap("HTTP/1.1 200 OK".getBytes(StandardCharsets.ISO_8859_1)),
                    new MutableFastGatewayHeaders());
            journal.requestBody(reqId, ByteBuffer.wrap(body));
            journal.endExchange(reqId, new FastGatewayAttributes(),
                    1L, 2L, 200,
                    // Zero request-body bytes, contradicting the body that was just written.
                    0L, 0L, 0L, 0L,
                    0L, 0L, 0L,
                    BodyChecksum.ofUnsigned32(0x0BADC0DEL), BodyChecksum.NOT_RECORDED);
        }

        final CollectingSink sink = tail();

        assertThat(sink.checksumMismatches)
                .as("neither a missing start event nor a zero byte count may excuse a recorded checksum. sink: %s", sink)
                .containsExactly(reqId + ":REQUEST");
    }

    /**
     * A sealed segment ending in fewer bytes than an entry header must be reported and
     * consumed.
     * <p>
     * The decode loop only runs while at least {@code MIN_ENTRY_SIZE} bytes remain, so a
     * shorter suffix is examined by nothing at all. Left behind, it is the same trap as
     * every other early exit: the tailer sees bytes remaining, never finishes the segment,
     * and checkpoints the identical offset on every tick for ever, silently.
     */
    @Test
    void sealedSegmentEndingMidHeaderIsReportedAndConsumed() throws IOException
    {
        writeExchanges(5);
        final Path segment = onlySealedSegment();

        final List<EntryRef> entries = entriesOf(segment);
        final EntryRef last = entries.get(entries.size() - 1);

        // Four bytes into the final entry: enough for the magic, far short of a header.
        try (var channel = java.nio.channels.FileChannel.open(segment, StandardOpenOption.WRITE))
        {
            channel.truncate(last.offset() + 4L);
        }

        final CollectingSink sink = tail();

        assertThat(sink.corruptRegions)
                .as("the unexaminable suffix must be reported, not ignored. sink: %s", sink)
                .isNotEmpty();

        // Second tick: the segment was consumed, so there is nothing left to re-report.
        final CollectingSink second = tail();
        assertThat(second.corruptRegions)
                .as("a consumed segment must not be read again on the next tick")
                .isEmpty();
    }

    /**
     * Recovery may only delete a segment it can prove was never used.
     * <p>
     * A stamped segment whose bytes cannot be read as entries is the worst case to get
     * wrong: the scan finds no records, so the file looks exactly like an untouched
     * pre-allocation, while those bytes are the only surviving evidence of what was
     * written. Deleting makes lost audit data indistinguishable from a spare.
     */
    @Test
    void stampedSegmentWithUnreadableDataIsQuarantinedNotDeleted() throws IOException
    {
        writeExchanges(1);
        final Path sealed = onlySealedSegment();
        final byte[] sealedBytes = Files.readAllBytes(sealed);

        // A real preamble, so the file is unmistakably a stamped segment, followed by bytes
        // that carry no magic anywhere.
        final byte[] damaged = new byte[SEGMENT_SIZE];
        System.arraycopy(sealedBytes, 0, damaged, 0, R7fConstants.PREAMBLE_SIZE);
        java.util.Arrays.fill(damaged, R7fConstants.PREAMBLE_SIZE, R7fConstants.PREAMBLE_SIZE + 512, (byte) 0xFF);

        final Path activePath = journalDir.resolve(activeNameFor(sealed));
        Files.delete(sealed);
        Files.write(activePath, damaged);

        final CollectingSink sink = new CollectingSink();
        R7fRecoveryManager.cleanAndRecover(journalDir, sink);

        assertThat(sink.quarantined).as("sink: %s", sink).hasSize(1);
        assertThat(quarantinedFiles()).hasSize(1);
        assertThat(Files.exists(activePath)).as("the evidence must not be deleted").isFalse();
    }

    /**
     * A hole at the very start of a sealed segment's data must be crossed like any other.
     * <p>
     * This is the first thing the decoder examines on a fresh read, and for a long time it
     * was the one position where the resync scan could not work: the caller maps the file
     * little-endian for FlatBuffers, and only {@code parseEntry} set big-endian for the
     * framing — so on iteration one the scan compared the magic byte-reversed, matched
     * nothing, and concluded the segment ended at offset 1024. The tailer then consumed it,
     * marked it fully read and deleted it, with every surviving entry unread and no loss
     * reported. A hole anywhere later happened to work, because a successful parse had set
     * the order on the way past.
     */
    @Test
    void holeAtTheStartOfASealedSegmentIsCrossed() throws IOException
    {
        writeExchanges(6);
        final Path segment = onlySealedSegment();

        final List<EntryRef> entries = entriesOf(segment);
        final EntryRef first = entries.get(0);
        final EntryRef second = entries.get(1);

        // Zeroes, not rubbish: a page that never reached the device reads back as zero, and
        // that is the branch this exercises.
        overwrite(segment, first.offset(), new byte[first.totalLength() + second.totalLength()]);

        final CollectingSink sink = tail();

        assertThat(sink.missingEntries)
                .as("the entries after the hole prove the loss through their sequence. sink: %s", sink)
                .isGreaterThan(0L);
        assertThat(sink.completed.size() + sink.orphanedEnds.size() + sink.incompleteEnds.size())
                .as("the reader must reach the entries after the hole, not stop at it")
                .isGreaterThan(0);
        assertThat(sink.corruptRegions).isNotEmpty();
    }

    /**
     * A sequence regression in a sealed segment abandons the rest of the file. That is the
     * right call — the segment is not a valid append-only log any more — but it must be
     * accounted for.
     * <p>
     * The branch used to consume to the end of the buffer without counting anything, so
     * {@code DecodeStats.isClean()} said the read was clean, the tailer saw a drained buffer,
     * marked the segment fully read and deleted it. Entries it had deliberately declined to
     * read were destroyed on the strength of having declined to read them.
     */
    @Test
    void sequenceRegressionInASealedSegmentIsAccountedFor() throws IOException
    {
        writeExchanges(6);
        final Path segment = onlySealedSegment();

        final List<EntryRef> entries = entriesOf(segment);
        final EntryRef victim = entries.get(entries.size() / 2);
        assertThat(victim.sequence()).isGreaterThan(R7fConstants.FIRST_ENTRY_SEQUENCE);

        // Framing and CRC stay valid; only the sequence steps backwards.
        rewriteSequence(segment, victim, R7fConstants.FIRST_ENTRY_SEQUENCE);

        final CollectingSink sink = tail();

        assertThat(sink.sequenceRegressions).as("sink: %s", sink).hasSize(1);
        assertThat(sink.corruptRegions)
                .as("the abandoned remainder must be reported, not silently dropped")
                .isNotEmpty();
    }

    /**
     * Recovery must treat an unpublished entry as the end of the data, not as damage.
     * <p>
     * Because the writer stamps the magic last, a process killed mid-entry leaves the entry
     * fully assembled with a zero magic slot. That is indistinguishable from "nothing was
     * ever written here", which is the point: recovery seals at that offset, reports
     * nothing wrong, and every published entry before it survives. With the magic written
     * first, the same crash left a recognisable-but-broken entry, and recovery had to guess.
     */
    @Test
    void recoverySealsAtAnUnpublishedEntryWithoutReportingDamage() throws IOException
    {
        writeExchanges(4);
        final Path sealed = onlySealedSegment();
        final byte[] complete = Files.readAllBytes(sealed);
        final List<EntryRef> entries = entriesOf(sealed);
        final EntryRef last = entries.get(entries.size() - 1);

        // The segment as the writer would have left it: everything in place, the final
        // entry's magic not yet stamped.
        final byte[] active = new byte[SEGMENT_SIZE];
        System.arraycopy(complete, 0, active, 0, complete.length);
        java.util.Arrays.fill(active, last.offset(), last.offset() + Integer.BYTES, (byte) 0);

        final Path activePath = journalDir.resolve(activeNameFor(sealed));
        Files.delete(sealed);
        Files.write(activePath, active);

        final CollectingSink recovery = new CollectingSink();
        R7fRecoveryManager.cleanAndRecover(journalDir, recovery);

        assertThat(recovery.corruptRegions)
                .as("an unpublished entry is not damage. sink: %s", recovery)
                .isEmpty();
        assertThat(recovery.quarantined).isEmpty();

        final Path resealed = onlySealedSegment();
        final ByteBuffer header = ByteBuffer.wrap(Files.readAllBytes(resealed)).order(ByteOrder.BIG_ENDIAN);
        assertThat(header.getLong(R7fConstants.PREAMBLE_OFF_DATA_END))
                .as("the segment's data must end exactly where the unpublished entry began")
                .isEqualTo(last.offset());
        assertThat(Files.size(resealed))
                .as("and the pre-allocated tail must be left alone rather than truncated away")
                .isGreaterThan(last.offset());

        final CollectingSink sink = tail();
        assertThat(sink.missingEntries)
                .as("nothing was lost, so nothing may be reported missing. sink: %s", sink)
                .isZero();
        assertThat(sink.corruptRegions).isEmpty();
    }

    /**
     * Entries lost from the end of a sealed segment must be reported.
     * <p>
     * Nothing else can catch this. A truncated tail leaves no gap and no damaged entry — the
     * segment reads back as a shorter, wholly self-consistent one, and the sequence check has
     * nothing to compare the end against. The seal record is what closes it: the writer notes
     * the last sequence it wrote, and the reader compares its own.
     */
    @Test
    void entriesLostFromTheEndOfASealedSegmentAreReported() throws IOException
    {
        writeExchanges(6);
        final Path segment = onlySealedSegment();

        final List<EntryRef> entries = entriesOf(segment);
        final EntryRef cutAt = entries.get(entries.size() - 3);

        // The last three entries never reached the device.
        try (var channel = java.nio.channels.FileChannel.open(segment, StandardOpenOption.WRITE))
        {
            channel.truncate(cutAt.offset());
        }

        final CollectingSink sink = tail();

        assertThat(sink.missingEntries)
                .as("the seal record is the only thing that can see a lost tail. sink: %s", sink)
                .isEqualTo(3L);
    }

    /**
     * A sealed segment records what it holds, and a healthy writer's count and last sequence
     * agree. Recovery is the documented exception, so this checks the writer's own path.
     */
    @Test
    void sealingRecordsWhatTheSegmentHolds() throws IOException
    {
        writeExchanges(4);
        final Path segment = onlySealedSegment();

        final ByteBuffer header = ByteBuffer.wrap(Files.readAllBytes(segment)).order(ByteOrder.BIG_ENDIAN);
        final List<EntryRef> entries = entriesOf(segment);

        assertThat(header.getInt(R7fConstants.PREAMBLE_OFF_SEAL_MAGIC))
                .as("a sealed segment must say so in its bytes, not only in its name")
                .isEqualTo(R7fConstants.SEAL_MAGIC);
        assertThat(header.getLong(R7fConstants.PREAMBLE_OFF_ENTRY_COUNT)).isEqualTo(entries.size());
        assertThat(header.getInt(R7fConstants.PREAMBLE_OFF_LAST_SEQUENCE))
                .isEqualTo(entries.get(entries.size() - 1).sequence());
    }

    /**
     * An active segment carries no seal record. Reading one would mean trusting zeroes as an
     * entry count.
     */
    @Test
    void anActiveSegmentCarriesNoSealRecord() throws IOException
    {
        writeExchanges(2);
        final Path sealed = onlySealedSegment();
        final byte[] bytes = Files.readAllBytes(sealed);

        final byte[] active = new byte[SEGMENT_SIZE];
        System.arraycopy(bytes, 0, active, 0, bytes.length);
        // Undo the seal: this is what the file looked like before rotation stamped it.
        java.util.Arrays.fill(active, R7fConstants.PREAMBLE_OFF_SEAL_MAGIC,
                R7fConstants.PREAMBLE_OFF_LAST_SEQUENCE + Integer.BYTES, (byte) 0);

        final Path activePath = journalDir.resolve(activeNameFor(sealed));
        Files.delete(sealed);
        Files.write(activePath, active);

        final CollectingSink sink = tail();

        // An active segment is never "finished", so the seal record is never consulted and
        // its absence is not a complaint.
        assertThat(sink.corruptRegions).as("sink: %s", sink).isEmpty();
        assertThat(sink.missingEntries).isZero();
    }

    /**
     * A segment the reader gave up on must survive, even under eager retention.
     * <p>
     * A sequence regression is the one case where the reader stops on bytes it could still
     * have decoded — the segment is no longer a valid append-only log, so it refuses to
     * replay the rest rather than emit records that may never have happened. Those entries
     * are still there and still readable, so deleting the file is the only thing that would
     * actually destroy them.
     */
    @Test
    void aSegmentWithUndeliveredEntriesIsKept() throws IOException
    {
        writeExchanges(6);
        final Path segment = onlySealedSegment();

        final List<EntryRef> entries = entriesOf(segment);
        rewriteSequence(segment, entries.get(entries.size() / 2), R7fConstants.FIRST_ENTRY_SEQUENCE);

        // No minimum age: retention would remove this the moment it was marked processed.
        final CollectingSink sink = new CollectingSink();
        new R7Tailer(journalDir, null, sink, sink,
                ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1))).runTick();

        assertThat(sink.sequenceRegressions).as("sink: %s", sink).hasSize(1);
        assertThat(Files.exists(segment))
                .as("entries the reader declined to deliver are still readable — deleting is what loses them")
                .isTrue();

        // And it is not read again: a second tick must not replay what it did deliver.
        final CollectingSink second = new CollectingSink();
        new R7Tailer(journalDir, null, second, second,
                ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1))).runTick();

        assertThat(second.completed).as("a kept segment must not be re-read. sink: %s", second).isEmpty();
        assertThat(Files.exists(segment)).isTrue();
    }

    /**
     * A segment that merely lost bytes is still deleted once read.
     * <p>
     * The counterpart to the test above, and the reason the two are separated: a hole is
     * unreadable to anyone, so keeping the file preserves forensics and nothing else. Only
     * abandonment holds data that deleting would destroy.
     */
    @Test
    void aSegmentWithDamageIsStillDeletedOnceRead() throws IOException
    {
        writeExchanges(6);
        final Path segment = onlySealedSegment();

        final List<EntryRef> entries = entriesOf(segment);
        final EntryRef victim = entries.get(entries.size() / 2);
        final byte[] rubbish = new byte[victim.totalLength()];
        java.util.Arrays.fill(rubbish, (byte) 0xFF);
        overwrite(segment, victim.offset(), rubbish);

        final CollectingSink sink = new CollectingSink();
        new R7Tailer(journalDir, null, sink, sink,
                ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1))).runTick();

        assertThat(sink.corruptRegions).as("sink: %s", sink).isNotEmpty();
        assertThat(sink.sequenceRegressions).as("damage, not abandonment").isEmpty();
        assertThat(Files.exists(segment))
                .as("the lost bytes are lost whatever we do with the file")
                .isFalse();
    }

    /**
     * A consumer that refuses an entry must not lose it.
     * <p>
     * Skipping the entry looks like tolerance and is destruction: the tailer checkpoints
     * past a record nobody received, the segment then reads as fully processed, and the
     * next tick deletes it. A sink that was unavailable for one tick would cost an
     * exchange, permanently, with nothing left to recover it from.
     * <p>
     * So the reader stops on the refused entry and offers it again. Everything after it in
     * that segment waits — head-of-line blocking on purpose, because an audit log may stall
     * loudly but may not skip quietly.
     */
    @Test
    void anEntryTheConsumerRefusesIsOfferedAgainRatherThanSkipped() throws IOException
    {
        writeExchanges(6);
        final Path segment = onlySealedSegment();

        final RefusingSink sink = new RefusingSink("req-2");
        // No minimum age: if the segment is ever considered finished it goes immediately,
        // which is the failure this test exists to catch.
        final R7Tailer tailer = new R7Tailer(journalDir, null, sink, sink,
                ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1)));

        tailer.runTick();

        assertThat(sink.delivered)
                .as("delivery stops at the refused entry. sink: %s", sink)
                .containsExactly("req-0", "req-1");
        assertThat(sink.stalls).as("and the stall is reported. sink: %s", sink).hasSize(1);
        assertThat(sink.corruptRegions)
                .as("a consumer failure is not damage to the journal. sink: %s", sink)
                .isEmpty();
        assertThat(Files.exists(segment))
                .as("a segment holding an undelivered entry must survive")
                .isTrue();

        // The sink recovers, as a sink that was briefly unavailable does.
        sink.acceptEverything();
        tailer.runTick();

        assertThat(sink.delivered)
                .as("the refused entry is delivered once, and the rest follow. sink: %s", sink)
                .containsExactly("req-0", "req-1", "req-2", "req-3", "req-4", "req-5");
        assertThat(sink.orphanedEnds)
                .as("the exchange state gathered before the refusal must survive it. sink: %s", sink)
                .isEmpty();
        assertThat(Files.exists(segment))
                .as("and only now, with everything delivered, may the segment go")
                .isFalse();
    }

    /**
     * A consumer that refuses the <em>mismatch report</em> must lose no more than one that
     * refuses the exchange itself.
     * <p>
     * Verification reports through {@code onChecksumMismatch}, which is consumer code like
     * any other. Running it outside the region that restores the in-flight exchange meant a
     * throw from there left the reassembler having already taken the exchange out of its
     * map: the decoder rewound the end entry as designed, and the retry found nothing to
     * attach it to. The record came back as an orphaned end with its start line, headers and
     * every body fragment gone — a worse outcome than the skip this whole mechanism replaced,
     * reached through the mechanism itself.
     */
    @Test
    void aConsumerThatRefusesAMismatchReportDoesNotLoseTheExchange() throws IOException
    {
        final String reqId = "req-refused-mismatch";
        final byte[] body = "the original body".getBytes(StandardCharsets.ISO_8859_1);

        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, SEGMENT_SIZE, true)))
        {
            journal.clientRequest(JournalLevel.FULL, reqId, ByteBuffer.wrap("GET / HTTP/1.1".getBytes(StandardCharsets.ISO_8859_1)),
                    new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
            journal.requestBody(reqId, ByteBuffer.wrap(body));
            journal.endExchange(reqId, new FastGatewayAttributes(),
                    1L, 2L, 200, 0L, body.length, 0L, 0L, 0L, 0L, 0L,
                    BodyChecksum.ofUnsigned32(0x0BADC0DE), BodyChecksum.NOT_RECORDED);
        }

        final RefusingSink sink = RefusingSink.refusingTheNextMismatchReport();
        final R7Tailer tailer = new R7Tailer(journalDir, null, sink, sink,
                ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1)));

        tailer.runTick();

        assertThat(sink.stalls).as("the refused report stalls the segment. sink: %s", sink).hasSize(1);
        assertThat(sink.delivered).isEmpty();

        tailer.runTick();

        assertThat(sink.orphanedEnds)
                .as("the retry must find the exchange the first attempt had removed. sink: %s", sink)
                .isEmpty();
        assertThat(sink.delivered).containsExactly(reqId);
        assertThat(sink.mismatches)
                .as("and the mismatch itself must still be reported")
                .containsExactly(reqId + ":REQUEST");
        assertThat(CollectingSink.concat(sink.lastExchange.getRequestBodyFragments()))
                .as("with the body that was assembled before the refusal")
                .isEqualTo(body);
    }

    /**
     * What recovery reports as discarded must be the content it could not read, not the
     * region that content sits in.
     * <p>
     * A segment is pre-allocated at full size, so "everything past the last valid entry" is
     * almost entirely untouched tail. Answering the question as a yes/no — is the remainder
     * all zeroes? — made a single torn entry report the whole pre-allocation as lost, which
     * is a six-figure integrity metric for a few dozen bytes of damage. An operator who
     * cannot trust the number cannot use it.
     */
    @Test
    void recoveryReportsOnlyTheContentItCouldNotRead() throws IOException
    {
        writeExchanges(4);
        final Path sealed = onlySealedSegment();

        final byte[] active = Files.readAllBytes(sealed);
        final int preAllocatedSize = active.length;
        java.util.Arrays.fill(active, R7fConstants.PREAMBLE_OFF_SEAL_MAGIC,
                R7fConstants.PREAMBLE_OFF_SEAL_FLAGS + Integer.BYTES, (byte) 0);

        // A torn entry right after the last valid one: a plausible magic, then rubbish. The
        // 200-odd kilobytes behind it stay zero, exactly as the warmer left them.
        final List<EntryRef> entries = entriesOf(sealed);
        final EntryRef last = entries.get(entries.size() - 1);
        final int tornStart = last.offset() + last.totalLength();
        final int tornLength = 40;
        java.util.Arrays.fill(active, tornStart, tornStart + tornLength, (byte) 0xAB);

        final Path activePath = journalDir.resolve(activeNameFor(sealed));
        Files.delete(sealed);
        Files.write(activePath, active);

        final CollectingSink recovery = new CollectingSink();
        R7fRecoveryManager.cleanAndRecover(journalDir, recovery);

        assertThat(recovery.recoveredSegments).as("recovery sink: %s", recovery).hasSize(1);
        assertThat(recovery.recoveryDiscardedBytes)
                .as("the damage is %d bytes, not the %d-byte pre-allocation it sits in. sink: %s",
                        tornLength, preAllocatedSize, recovery)
                .containsExactly((long) tornLength);
    }

    /**
     * Recovery can leave entries in a segment that it deliberately did not publish, and the
     * tailer must not delete such a segment however clean its own read was.
     * <p>
     * This is the one case the tailer cannot work out for itself. Recovery stops at a
     * sequence regression and seals at that point, so everything after it lies past Data End
     * — outside what any reader is allowed to look at. The tailer therefore sees a short,
     * entirely healthy segment, reads it to the end without a single anomaly, and would
     * delete it. The entries recovery was careful to preserve would be destroyed by the one
     * component whose own regression handling exists to preserve them, which is why the seal
     * record carries the decision across the handover.
     */
    @Test
    void aSegmentRecoveryShortenedAroundARegressionIsNeverDeleted() throws IOException
    {
        writeExchanges(6);
        final Path sealed = onlySealedSegment();

        // Put it back the way an unclean stop would have left it: same bytes, no seal record.
        final byte[] active = Files.readAllBytes(sealed);
        java.util.Arrays.fill(active, R7fConstants.PREAMBLE_OFF_SEAL_MAGIC,
                R7fConstants.PREAMBLE_OFF_SEAL_FLAGS + Integer.BYTES, (byte) 0);
        final Path activePath = journalDir.resolve(activeNameFor(sealed));
        Files.delete(sealed);
        Files.write(activePath, active);

        final List<EntryRef> entries = entriesOf(activePath);
        final EntryRef victim = entries.get(entries.size() / 2);
        rewriteSequence(activePath, victim, R7fConstants.FIRST_ENTRY_SEQUENCE);

        final CollectingSink recovery = new CollectingSink();
        R7fRecoveryManager.cleanAndRecover(journalDir, recovery);
        assertThat(recovery.sequenceRegressions).as("recovery sink: %s", recovery).hasSize(1);

        final Path resealed = onlySealedSegment();
        final ByteBuffer header = ByteBuffer.wrap(Files.readAllBytes(resealed)).order(ByteOrder.BIG_ENDIAN);
        assertThat(header.getLong(R7fConstants.PREAMBLE_OFF_DATA_END))
                .as("recovery seals at the regression, hiding what follows")
                .isEqualTo(victim.offset());
        assertThat(header.getInt(R7fConstants.PREAMBLE_OFF_SEAL_FLAGS) & R7fConstants.SEAL_FLAG_RETAIN)
                .as("and records that what it hid is content, not damage")
                .isNotZero();

        final CollectingSink sink = new CollectingSink();
        new R7Tailer(journalDir, null, sink, sink,
                ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1))).runTick();

        assertThat(sink.isClean())
                .as("the tailer's own read is spotless — which is exactly the trap. sink: %s", sink)
                .isTrue();
        assertThat(Files.exists(resealed))
                .as("and it must still not delete a segment recovery asked to be kept")
                .isTrue();

        // Nor may it read it again: finished is finished, it is only not disposable.
        final CollectingSink second = new CollectingSink();
        new R7Tailer(journalDir, null, second, second,
                ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1))).runTick();

        assertThat(second.completed).as("a kept segment must not be replayed. sink: %s", second).isEmpty();
        assertThat(Files.exists(resealed)).isTrue();
    }

    /**
     * The in-flight ceiling must bound memory without ever evicting the exchange the
     * current event belongs to.
     * <p>
     * At {@code maxInFlight = 1} the distinction is not academic: a per-event check makes
     * every second event of the only exchange in flight evict it, so an exchange can never
     * be assembled at all and the setting is fatal rather than merely tight. Making room is
     * something admitting a <em>new</em> exchange does, and nothing else.
     */
    @Test
    void anExchangeSurvivesTheSmallestPossibleInFlightBudget() throws IOException
    {
        writeExchanges(1);

        final CollectingSink sink = new CollectingSink();
        new R7Tailer(journalDir, Duration.ofHours(1), sink, sink,
                ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1)).withMaxInFlight(1)).runTick();

        assertThat(sink.isClean()).as("sink: %s", sink).isTrue();
        assertThat(sink.completed)
                .as("the one exchange in flight must not be evicted by its own body event")
                .containsOnlyKeys("req-0");
    }

    /**
     * A consumer that refuses one exchange, records what it did receive, and can be told to
     * stop refusing — a sink that is briefly unavailable and then is not.
     */
    private static final class RefusingSink implements ExchangeCompletionListener, JournalIntegrityListener
    {
        final List<String> delivered = new ArrayList<>();
        final List<String> stalls = new ArrayList<>();
        final List<String> corruptRegions = new ArrayList<>();
        final List<String> orphanedEnds = new ArrayList<>();
        final List<String> mismatches = new ArrayList<>();
        JournalExchange lastExchange;

        private String refusing;
        private boolean refusingMismatch;

        RefusingSink(final String requestIdToRefuse)
        {
            this.refusing = requestIdToRefuse;
        }

        /**
         * Refuses once and then behaves, which is what a sink that was briefly unavailable
         * looks like — and what makes the retry observable rather than an infinite stall.
         */
        static RefusingSink refusingTheNextMismatchReport()
        {
            final RefusingSink sink = new RefusingSink(null);
            sink.refusingMismatch = true;
            return sink;
        }

        void acceptEverything()
        {
            refusing = null;
        }

        @Override
        public void onComplete(final JournalExchange exchange)
        {
            if (exchange.getRequestId().equals(refusing))
            {
                throw new IllegalStateException("sink unavailable for " + refusing);
            }
            lastExchange = exchange;
            delivered.add(exchange.getRequestId());
        }

        @Override
        public void onChecksumMismatch(final JournalExchange exchange, final BodyKind kind,
                                       final BodyChecksum journaled, final BodyChecksum observed)
        {
            if (refusingMismatch)
            {
                refusingMismatch = false;
                throw new IllegalStateException("sink unavailable for the mismatch report");
            }
            mismatches.add(exchange.getRequestId() + ":" + kind);
        }

        @Override
        public void onOrphanedEnd(final String requestId)
        {
            orphanedEnds.add(requestId);
        }

        @Override
        public void onCorruptRegion(final String segment, final long offset, final long bytesSkipped, final String reason)
        {
            corruptRegions.add(segment + "@" + offset + ":" + reason);
        }

        @Override
        public void onDeliveryStalled(final String segment, final long offset, final int sequence, final Throwable cause)
        {
            stalls.add(segment + "@" + offset + ":#" + sequence);
        }

        @Override
        public String toString()
        {
            return "delivered=" + delivered + ", stalls=" + stalls + ", mismatches=" + mismatches
                    + ", corruptRegions=" + corruptRegions + ", orphanedEnds=" + orphanedEnds;
        }
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
                        1L, 2L, 200, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                        BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);
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

    /**
     * Rewrites an entry's sequence number and repairs its CRC, so the entry stays
     * structurally valid and only the sequence is wrong. Mirrors the writer's CRC
     * coverage: sequence, the three lengths, then the payload.
     */
    private static void rewriteSequence(final Path file, final EntryRef entry, final int newSequence) throws IOException
    {
        final byte[] bytes = Files.readAllBytes(file);
        final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);

        buffer.putInt(entry.offset() + 4, newSequence);

        final CRC32C crc = new CRC32C();
        for (int field = 0; field < 4; field++)
        {
            final int value = buffer.getInt(entry.offset() + 4 + (field * Integer.BYTES));
            crc.update((value >>> 24) & 0xFF);
            crc.update((value >>> 16) & 0xFF);
            crc.update((value >>> 8) & 0xFF);
            crc.update(value & 0xFF);
        }

        final int dataLen = entry.fbLen() + entry.rawLen();
        if (dataLen > 0)
        {
            crc.update(bytes, entry.payloadOffset(), dataLen);
        }

        buffer.putInt(entry.payloadOffset() + dataLen, (int) crc.getValue());
        Files.write(file, bytes);
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

    /**
     * The active name a sealed segment was rotated from: the first four fields are the ones
     * sealing preserves, and the two timestamps are what it appends.
     */
    private static String activeNameFor(final Path sealedSegment)
    {
        final String[] parts = sealedSegment.getFileName().toString()
                .replace(R7fConstants.R7F_FILE_EXTENSION, "")
                .split("-");
        return String.join("-", parts[0], parts[1], parts[2], parts[3]) + R7fConstants.ACTIVE_FILE_EXTENSION;
    }

    private long sequenceOfOnlySegment() throws IOException
    {
        final String[] parts = onlySealedSegment().getFileName().toString()
                .replace(R7fConstants.R7F_FILE_EXTENSION, "")
                .split("-");
        return Long.parseLong(parts[3]);
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
