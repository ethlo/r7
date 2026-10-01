package com.ethlo.r7;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.R7fTestFraming.EntryRef;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
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
 * Format version 2's block framing (FORMAT.md 3.1, 4.3, 6): entries split across blocks,
 * padding, and what damage costs once the reader resumes only at block boundaries.
 * <p>
 * The arithmetic here is the test's own, from the spec, so that a writer and a reader that
 * agreed on a wrong placement would still fail.
 */
class JournalBlockFramingTest
{
    private static final long SEGMENT_SIZE = 1024L * 1024L;
    private static final int BLOCK = R7fConstants.DEFAULT_BLOCK_SIZE;
    private static final int HEADER = R7fTestFraming.HEADER;
    private static final int CONTENT_HEADER = R7fConstants.ENTRY_CONTENT_HEADER_SIZE;

    @TempDir
    Path journalDir;

    /**
     * An entry can start anywhere in a block, and when it does not fit it is split. The
     * awkward cases are near the end of a block: a FIRST fragment of one byte, FIRST fragments
     * that cut the entry's 12-byte head (sequence and lengths) in two, the last position that
     * still takes a fragment, the first that is padding, and an entry starting exactly on a
     * boundary. Every one of them must come back byte for byte.
     */
    @Test
    void entriesStartingAtEveryAwkwardOffsetNearABlockEndRoundTrip() throws IOException
    {
        final int[] bytesLeftInBlock = {0, 1, 15, 16, 17, 18, 20, 23, 27, 28, 29, 40, 200};
        final long[] targets = new long[bytesLeftInBlock.length];

        try (R7fJournal journal = journal())
        {
            final Filler filler = new Filler(journal);
            for (int i = 0; i < bytesLeftInBlock.length; i++)
            {
                final long boundary = (R7fTestFraming.skipPadding((int) journal.getOffset(), BLOCK) / BLOCK + 2L) * BLOCK;
                targets[i] = boundary - bytesLeftInBlock[i];
                filler.advanceTo(targets[i]);
                exchange(journal, "probe-" + bytesLeftInBlock[i], body(5_000, i));
            }
            filler.end();
        }

        final Path segment = onlySealedSegment();
        final List<EntryRef> entries = R7fTestFraming.entriesOf(segment);
        final byte[] bytes = Files.readAllBytes(segment);

        for (int i = 0; i < bytesLeftInBlock.length; i++)
        {
            final int left = bytesLeftInBlock[i];
            final long target = targets[i];
            final long expectedStart = left > 0 && left < HEADER + 1 ? target + left : target;
            final EntryRef probe = entries.stream().filter(e -> e.offset() == expectedStart).findFirst().orElseThrow(
                    () -> new AssertionError("no entry starts at " + expectedStart + " (" + left + " bytes left)"));

            if (left > 0 && left < HEADER + 1)
            {
                assertThat(Arrays.copyOfRange(bytes, (int) target, (int) expectedStart))
                        .as("%d bytes left in the block are padding: zero, and skipped by position", left)
                        .containsOnly(0);
            }
            else if (left >= HEADER + 1)
            {
                final long contentLength = CONTENT_HEADER + (long) probe.fbLen() + probe.rawLen();
                assertThat(probe.isSplit())
                        .as("%d content bytes against %d bytes left in the block", contentLength, left)
                        .isEqualTo(contentLength > left - HEADER);
                if (probe.isSplit())
                {
                    assertThat(probe.fragments().getFirst()[1])
                            .as("the FIRST fragment fills its block")
                            .isEqualTo(left - HEADER);
                    assertThat(probe.fragments().get(1)[0] % BLOCK).as("a continuation starts on a boundary").isZero();
                }
            }
        }

        final CollectingSink sink = tail();
        assertThat(sink.isClean()).as("sink: %s", sink).isTrue();
        for (int i = 0; i < bytesLeftInBlock.length; i++)
        {
            assertThat(CollectingSink.concat(sink.completed.get("probe-" + bytesLeftInBlock[i]).getRequestBodyFragments()))
                    .isEqualTo(body(5_000, i));
        }
    }

    /**
     * An entry larger than a block travels as FIRST, MIDDLE… LAST, each MIDDLE filling a whole
     * block, and is reassembled into exactly what was written.
     */
    @Test
    void anEntryLargerThanABlockIsCarriedInMiddleFragments() throws IOException
    {
        final byte[] large = body(5 * BLOCK + 123, 7);
        try (R7fJournal journal = journal())
        {
            exchange(journal, "small", body(10, 1));
            exchange(journal, "large", large);
            exchange(journal, "after", body(10, 2));
        }

        final Path segment = onlySealedSegment();
        final ByteBuffer file = ByteBuffer.wrap(Files.readAllBytes(segment)).order(ByteOrder.BIG_ENDIAN);
        final EntryRef carrier = R7fTestFraming.entriesOf(segment).stream()
                .filter(e -> e.rawLen() == large.length).findFirst().orElseThrow();

        final List<int[]> fragments = carrier.fragments();
        assertThat(fragments.size()).isGreaterThanOrEqualTo(6);
        assertThat(file.get(fragments.getFirst()[0] + 4)).isEqualTo(R7fConstants.FRAGMENT_FIRST);
        for (final int[] middle : fragments.subList(1, fragments.size() - 1))
        {
            assertThat(file.get(middle[0] + 4)).isEqualTo(R7fConstants.FRAGMENT_MIDDLE);
            assertThat(middle[1]).as("a MIDDLE fragment fills its block").isEqualTo(BLOCK - HEADER);
        }
        assertThat(file.get(fragments.getLast()[0] + 4)).isEqualTo(R7fConstants.FRAGMENT_LAST);

        final CollectingSink sink = tail();
        assertThat(sink.isClean()).as("sink: %s", sink).isTrue();
        assertThat(CollectingSink.concat(sink.completed.get("large").getRequestBodyFragments())).isEqualTo(large);
        assertThat(sink.completed).containsKeys("small", "after");
    }

    /**
     * Damage in a continuation costs its entry and nothing else. The reader goes back to the
     * entry's start, resumes at the next boundary, steps over the remaining continuations, and
     * picks up right after the LAST one.
     */
    @Test
    void aDamagedContinuationCostsOnlyItsEntry() throws IOException
    {
        final EntryRef carrier = writeAroundALargeBody();
        final Path segment = onlySealedSegment();

        final int[] middle = carrier.fragments().get(2);
        flipByte(segment, middle[0] + HEADER + 100);

        final CollectingSink sink = tail();
        assertThat(sink.corruptRegions).as("sink: %s", sink).hasSize(1);
        assertThat(sink.missingEntries).as("exactly the damaged entry").isEqualTo(1);
        assertThat(sink.completed).containsKeys("before-0", "before-19", "after-0", "after-19");
        // This test's exchanges declare no body bytes in their end event, so the exchange still
        // completes; what the lost entry carried was its body.
        assertThat(sink.completed.get("large").getRequestBodyFragments()).isEmpty();
    }

    /**
     * A hole that takes out the FIRST fragment leaves its continuations behind. At the next
     * boundary the reader finds a MIDDLE whose entry it never saw the start of: it is verified,
     * skipped and counted in the reported region, and reading resumes after the LAST.
     */
    @Test
    void continuationsWhoseFirstFragmentWasLostAreSkipped() throws IOException
    {
        final EntryRef carrier = writeAroundALargeBody();
        final Path segment = onlySealedSegment();

        final int first = carrier.offset();
        final int boundary = (first / BLOCK + 1) * BLOCK;
        overwrite(segment, first, new byte[boundary - first]);

        final CollectingSink sink = tail();
        assertThat(sink.corruptRegions).as("one region, however many steps it took. sink: %s", sink).hasSize(1);
        assertThat(sink.missingEntries).isEqualTo(1);
        assertThat(sink.completed).containsKeys("before-19", "after-0", "after-19");
    }

    /**
     * FORMAT.md 6.1: payload bytes are never read as framing.
     * <p>
     * A request body carries complete, CRC-valid fragments for an exchange that never
     * happened, placed right after damage in the same block. Version 1 resynchronised by
     * scanning for the next magic, which would have landed in this body and delivered the
     * forgery as genuine. Version 2 resumes only at the next block boundary, where the writer
     * placed a real header, so the forgery is stepped over with the rest of the block, while
     * the genuine entries beyond the boundary are delivered.
     */
    @Test
    void aPayloadThatForgesEntriesIsNeverDelivered() throws IOException
    {
        final byte[] forgery = forgedExchange("forged");
        final byte[] carrierBody = new byte[64 + forgery.length + 64];
        final int carrierStart;
        System.arraycopy(forgery, 0, carrierBody, 64, forgery.length);

        try (R7fJournal journal = journal())
        {
            final Filler filler = new Filler(journal);
            // Start the carrier near the beginning of a block, so the forgery sits well inside it
            filler.advanceTo((R7fTestFraming.skipPadding((int) journal.getOffset(), BLOCK) / BLOCK + 2L) * BLOCK + 1024);
            filler.end();
            carrierStart = R7fTestFraming.skipPadding((int) journal.getOffset(), BLOCK);
            exchange(journal, "carrier", carrierBody);
            for (int i = 0; i < 300; i++)
            {
                exchange(journal, "after-" + i, body(40, i));
            }
        }

        final Path segment = onlySealedSegment();
        final byte[] bytes = Files.readAllBytes(segment);
        final List<EntryRef> entries = R7fTestFraming.entriesOf(segment);
        final EntryRef carrierRequest = entries.stream().filter(e -> e.offset() == carrierStart).findFirst().orElseThrow();
        final int forgeryAt = indexOf(bytes, forgery);
        assertThat(forgeryAt).as("the forgery is in the file, intact").isPositive();
        assertThat(forgeryAt / BLOCK)
                .as("and in the same block as the damage, after it")
                .isEqualTo(carrierRequest.offset() / BLOCK);
        assertThat(forgeryAt).isGreaterThan(carrierRequest.offset());

        // Damage the entry just before the forgery: the carrier's own client request
        flipByte(segment, carrierRequest.payloadOffset() + 3);

        final CollectingSink sink = tail();
        assertThat(sink.toString()).as("nothing forged may surface anywhere").doesNotContain("forged");
        assertThat(sink.completed).doesNotContainKey("forged");
        assertThat(sink.corruptRegions).hasSize(1);
        assertThat(sink.completed).as("genuine entries past the next boundary").containsKey("after-299");
    }

    /**
     * A crash between writing a split entry's continuations and stamping its FIRST magic
     * leaves complete continuations behind an uncommitted start. That is not damage: recovery
     * seals at the entry's start and reports nothing, exactly as for an unpublished FULL entry.
     */
    @Test
    void recoverySealsBeforeAnUncommittedSplitEntry() throws IOException
    {
        try (R7fJournal journal = journal())
        {
            for (int i = 0; i < 5; i++)
            {
                exchange(journal, "before-" + i, body(40, i));
            }
            journal.clientRequest(JournalLevel.FULL, "large", line("large"), new MutableFastGatewayHeaders(),
                    InetAddress.getLoopbackAddress(), IpSource.SOCKET);
            journal.requestBody("large", ByteBuffer.wrap(body(3 * BLOCK, 9)));
        }

        final Path sealed = onlySealedSegment();
        final List<EntryRef> entries = R7fTestFraming.entriesOf(sealed);
        final EntryRef last = entries.getLast();
        assertThat(last.isSplit()).isTrue();

        final byte[] active = Files.readAllBytes(sealed);
        Arrays.fill(active, last.offset(), last.offset() + Integer.BYTES, (byte) 0);
        final Path activePath = journalDir.resolve(activeNameFor(sealed));
        Files.delete(sealed);
        Files.write(activePath, active);

        final CollectingSink recovery = new CollectingSink();
        R7fRecoveryManager.cleanAndRecover(journalDir, recovery);
        assertThat(recovery.corruptRegions).as("an uncommitted entry is not damage. sink: %s", recovery).isEmpty();
        assertThat(recovery.quarantined).isEmpty();

        final ByteBuffer header = ByteBuffer.wrap(Files.readAllBytes(onlySealedSegment())).order(ByteOrder.BIG_ENDIAN);
        assertThat(header.getLong(R7fConstants.PREAMBLE_OFF_DATA_END)).isEqualTo(last.offset());
        assertThat(header.getLong(R7fConstants.PREAMBLE_OFF_ENTRY_COUNT)).isEqualTo(entries.size() - 1);

        final CollectingSink sink = tail();
        assertThat(sink.missingEntries).as("sink: %s", sink).isZero();
        assertThat(sink.corruptRegions).isEmpty();
        assertThat(sink.completed).containsKey("before-4");
    }

    /**
     * A reader tailing the active segment sees a split entry only once it is whole, and
     * resumes correctly across padding and continuations from one tick to the next.
     */
    @Test
    void anActiveSegmentIsTailedAcrossSplitEntries() throws IOException
    {
        final CollectingSink sink = new CollectingSink();
        final R7Tailer tailer = new R7Tailer(journalDir, sink, sink, ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1)));

        try (R7fJournal journal = journal())
        {
            for (int i = 0; i < 6; i++)
            {
                exchange(journal, "x-" + i, body(i % 2 == 0 ? 2 * BLOCK + i : 100 + i, i));
                tailer.runTick();
            }
        }
        tailer.runTick();

        assertThat(sink.isClean()).as("sink: %s", sink).isTrue();
        for (int i = 0; i < 6; i++)
        {
            assertThat(CollectingSink.concat(sink.completed.get("x-" + i).getRequestBodyFragments()))
                    .isEqualTo(body(i % 2 == 0 ? 2 * BLOCK + i : 100 + i, i));
        }
    }

    /**
     * A segment of another format version is refused (FORMAT.md 11) and set aside, never
     * deleted: having read nothing from it is no proof that it holds nothing.
     */
    @Test
    void aVersionOneSegmentIsSetAsideByRecoveryNotDeleted() throws IOException
    {
        final byte[] v1 = new byte[(int) R7fJournalProvider.MIN_SEGMENT_SIZE];
        final ByteBuffer b = ByteBuffer.wrap(v1).order(ByteOrder.BIG_ENDIAN);
        b.putInt(R7fConstants.PREAMBLE_OFF_MAGIC, R7fConstants.MAGIC);
        b.putShort(R7fConstants.PREAMBLE_OFF_VERSION, R7fConstants.VERSION_1);
        b.putInt(R7fConstants.PREAMBLE_SIZE, R7fConstants.MAGIC); // a version-1 entry magic
        b.putInt(R7fConstants.PREAMBLE_SIZE + 4, 1);
        final Path active = journalDir.resolve("shard-0-1700000000000-1" + R7fConstants.ACTIVE_FILE_EXTENSION);
        Files.write(active, v1);

        final CollectingSink recovery = new CollectingSink();
        R7fRecoveryManager.cleanAndRecover(journalDir, recovery);

        assertThat(recovery.quarantined).as("sink: %s", recovery).hasSize(1);
        assertThat(recovery.quarantined.getFirst()).contains("version 1");
        try (Stream<Path> files = Files.list(journalDir))
        {
            final List<Path> corrupt = files.filter(p -> p.toString().endsWith(R7fConstants.CORRUPT_FILE_EXTENSION)).toList();
            assertThat(corrupt).hasSize(1);
            assertThat(Files.readAllBytes(corrupt.getFirst())).as("kept byte for byte").isEqualTo(v1);
        }
    }

    /* ---------- helpers ---------- */

    /**
     * Twenty exchanges, one with a body spanning several blocks, twenty more.
     *
     * @return the large body's entry
     */
    private EntryRef writeAroundALargeBody() throws IOException
    {
        final byte[] large = body(4 * BLOCK, 3);
        try (R7fJournal journal = journal())
        {
            for (int i = 0; i < 20; i++)
            {
                exchange(journal, "before-" + i, body(40, i));
            }
            exchange(journal, "large", large);
            for (int i = 0; i < 20; i++)
            {
                exchange(journal, "after-" + i, body(40, i));
            }
        }
        final EntryRef carrier = R7fTestFraming.entriesOf(onlySealedSegment()).stream()
                .filter(e -> e.rawLen() == large.length).findFirst().orElseThrow();
        assertThat(carrier.fragments().size()).isGreaterThanOrEqualTo(4);
        return carrier;
    }

    /**
     * The fragments a writer would produce for a whole exchange — request, body, end — under
     * {@code reqId}, with sequence numbers far ahead so that a reader that accepted them would
     * deliver them rather than stop on a regression. Taken from a real journal written
     * elsewhere, so the payloads are genuine.
     */
    private byte[] forgedExchange(final String reqId) throws IOException
    {
        final Path elsewhere = Files.createDirectory(journalDir.resolve("elsewhere"));
        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(elsewhere, 0, SEGMENT_SIZE, false)))
        {
            exchange(journal, reqId, body(20, 5));
        }
        final Path source;
        try (Stream<Path> files = Files.list(elsewhere))
        {
            source = files.filter(p -> p.toString().endsWith(R7fConstants.R7F_FILE_EXTENSION)).findFirst().orElseThrow();
        }
        final ByteBuffer file = ByteBuffer.wrap(Files.readAllBytes(source)).order(ByteOrder.BIG_ENDIAN);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int sequence = 1_000_000;
        for (final EntryRef entry : R7fTestFraming.entriesOf(file))
        {
            final byte[] content = R7fTestFraming.contentOf(file, entry);
            ByteBuffer.wrap(content).putInt(0, sequence++);
            out.writeBytes(R7fTestFraming.fullFragment(content));
        }
        // The elsewhere directory must not be mistaken for journal content
        try (Stream<Path> files = Files.list(elsewhere))
        {
            for (final Path p : files.toList())
            {
                Files.delete(p);
            }
        }
        Files.delete(elsewhere);
        return out.toByteArray();
    }

    /**
     * Steers the writer to an exact offset with request bodies of chosen sizes, all on one
     * exchange so that they are delivered rather than reported as orphans.
     */
    private static final class Filler
    {
        private final R7fJournal journal;
        private final long overhead;

        Filler(final R7fJournal journal)
        {
            this.journal = journal;
            journal.clientRequest(JournalLevel.FULL, "fill", line("fill"), new MutableFastGatewayHeaders(),
                    InetAddress.getLoopbackAddress(), IpSource.SOCKET);
            final long before = journal.getOffset();
            fill(100);
            // Everything an entry costs besides its raw payload: the fragment header, the
            // content header and the FlatBuffer, which is the same size for every fill.
            this.overhead = journal.getOffset() - before - 100;
        }

        void advanceTo(final long target)
        {
            while (true)
            {
                final long start = R7fTestFraming.skipPadding((int) journal.getOffset(), BLOCK);
                assertThat(target).as("the filler only moves forward").isGreaterThanOrEqualTo(journal.getOffset());
                if (journal.getOffset() == target || (start == target && target % BLOCK == 0))
                {
                    return;
                }
                final long boundary = (start / BLOCK + 1) * BLOCK;
                if (target <= boundary && target - start >= overhead + 1)
                {
                    fill((int) (target - start - overhead));
                    assertThat(journal.getOffset()).as("filler arithmetic").isEqualTo(target);
                    return;
                }
                if (boundary - start >= overhead + 1)
                {
                    fill((int) (boundary - start - overhead));
                }
                else
                {
                    // Too little room for a whole fill: a one-byte fill splits into the next block
                    fill(1);
                }
            }
        }

        void end()
        {
            journal.endExchange("fill", new FastGatewayAttributes(), 1L, 2L, 200, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                    BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);
        }

        private void fill(final int size)
        {
            final byte[] bytes = new byte[size];
            Arrays.fill(bytes, (byte) 'f');
            journal.requestBody("fill", ByteBuffer.wrap(bytes));
        }
    }

    private R7fJournal journal()
    {
        return new R7fJournal(new R7fJournalProvider(journalDir, 0, SEGMENT_SIZE, false));
    }

    private static void exchange(final R7fJournal journal, final String reqId, final byte[] body)
    {
        journal.clientRequest(JournalLevel.FULL, reqId, line(reqId), new MutableFastGatewayHeaders(),
                InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        journal.requestBody(reqId, ByteBuffer.wrap(body));
        journal.endExchange(reqId, new FastGatewayAttributes(), 1L, 2L, 200, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);
    }

    private static ByteBuffer line(final String reqId)
    {
        return ByteBuffer.wrap(("POST /" + reqId + " HTTP/1.1").getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Deterministic, distinguishable content: no two bodies of the same length match. */
    private static byte[] body(final int length, final int seed)
    {
        final byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++)
        {
            bytes[i] = (byte) ('a' + (i * 7 + seed * 13) % 26);
        }
        return bytes;
    }

    private CollectingSink tail() throws IOException
    {
        final CollectingSink sink = new CollectingSink();
        new R7Tailer(journalDir, sink, sink, ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1))).runTick();
        return sink;
    }

    private Path onlySealedSegment() throws IOException
    {
        try (Stream<Path> files = Files.list(journalDir))
        {
            final List<Path> sealed = files.filter(p -> p.toString().endsWith(R7fConstants.R7F_FILE_EXTENSION)).toList();
            assertThat(sealed).as("expected exactly one sealed segment").hasSize(1);
            return sealed.getFirst();
        }
    }

    private static String activeNameFor(final Path sealedSegment)
    {
        final String[] parts = sealedSegment.getFileName().toString()
                .replace(R7fConstants.R7F_FILE_EXTENSION, "")
                .split("-");
        return String.join("-", parts[0], parts[1], parts[2], parts[3]) + R7fConstants.ACTIVE_FILE_EXTENSION;
    }

    private static int indexOf(final byte[] haystack, final byte[] needle)
    {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++)
        {
            for (int j = 0; j < needle.length; j++)
            {
                if (haystack[i + j] != needle[j])
                {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
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
        System.arraycopy(replacement, 0, bytes, offset, replacement.length);
        Files.write(file, bytes);
    }
}
