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
import com.ethlo.r7.journal.api.JournalExchange;
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
                    0x0BADC0DE, JournalExchange.CHECKSUM_NOT_RECORDED);
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
     * tail. A sealed segment is truncated to its exact size, so zeroes inside one are a
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
                        JournalExchange.CHECKSUM_NOT_RECORDED, JournalExchange.CHECKSUM_NOT_RECORDED);
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
                    0x12345678, JournalExchange.CHECKSUM_NOT_RECORDED);
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
                    0, JournalExchange.CHECKSUM_NOT_RECORDED);
        }

        final CollectingSink sink = tail();

        assertThat(sink.checksumMismatches).as("sink: %s", sink).containsExactly(reqId + ":REQUEST");
    }

    /**
     * A compressed segment whose frame cannot be read must be set aside, not marked
     * processed — which would let the tailer's own clean-up delete it.
     */
    @Test
    void unreadableCompressedSegmentIsQuarantined() throws IOException
    {
        final Path compressed = journalDir.resolve(
                "shard-0-1700000000000-1-1-2" + R7fConstants.R7F_FILE_EXTENSION + R7fConstants.COMPRESSED_FILE_EXTENSION);
        final byte[] notZstd = new byte[512];
        java.util.Arrays.fill(notZstd, (byte) 'Q');
        Files.write(compressed, notZstd);

        final CollectingSink sink = tail();

        assertThat(sink.quarantined).as("sink: %s", sink).hasSize(1);
        assertThat(Files.exists(compressed)).as("an audit segment must not be deleted unread").isFalse();
        assertThat(quarantinedFiles()).hasSize(1);
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
                    0xFFFFFFFFL, JournalExchange.CHECKSUM_NOT_RECORDED);
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
        // the full pre-allocation, and the final entry has its header but not its payload.
        final byte[] active = new byte[SEGMENT_SIZE];
        final int partialBytes = last.offset() + R7fConstants.ENTRY_HEADER_SIZE + 1;
        System.arraycopy(complete, 0, active, 0, partialBytes);

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

        // The writer finishes the entry.
        System.arraycopy(complete, partialBytes, active, partialBytes, complete.length - partialBytes);
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
                        JournalExchange.CHECKSUM_NOT_RECORDED, JournalExchange.CHECKSUM_NOT_RECORDED);
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
