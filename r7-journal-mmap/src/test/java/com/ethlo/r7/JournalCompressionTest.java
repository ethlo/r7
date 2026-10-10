package com.ethlo.r7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ethlo.r7.R7fTestFraming.EntryRef;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.R7Tailer;
import com.ethlo.r7.r7f.R7fConstants;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.r7f.R7fJournalProvider;
import com.ethlo.r7.r7f.R7fJournalTestAccess;
import com.ethlo.r7.r7f.R7fRecoveryManager;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * Codec 2 (FORMAT.md 4.4): entries staged by request threads and placed by a writer thread,
 * a batch at a time, each batch one zstd frame committed by one magic. What it must keep from
 * the uncompressed format: nothing visible before its magic, damage bounded, Sequences that
 * prove loss, and readers able to resume inside a batch. Entry fields here come from
 * {@link R7fTestFraming}, which decodes batches on its own.
 */
class JournalCompressionTest
{
    private static final long SEGMENT_SIZE = 1024L * 1024L;
    private static final int BLOCK = R7fConstants.DEFAULT_BLOCK_SIZE;

    @TempDir
    Path journalDir;

    /**
     * Small entries share batches and entries larger than a stage get one of their own; both
     * round-trip byte for byte, and the journal is a fraction of the same one uncompressed.
     */
    @Test
    void batchesRoundTripAndTheJournalShrinks() throws IOException
    {
        final Path compressed = Files.createDirectory(journalDir.resolve("zstd"));
        final Path plain = Files.createDirectory(journalDir.resolve("none"));
        writeMixed(compressed, 1);
        writeMixed(plain, 0);

        final Path segment = onlySealedSegment(compressed);
        assertThat(header(segment).getShort(R7fConstants.PREAMBLE_OFF_CODEC)).isEqualTo(R7fConstants.CODEC_ZSTD_BATCH);

        final List<EntryRef> entries = R7fTestFraming.entriesOf(segment);
        assertThat(entries).hasSize(1200);
        assertThat(entries).extracting(EntryRef::sequence)
                .as("one run of Sequences across every batch")
                .isEqualTo(IntStream.rangeClosed(1, 1200).boxed().toList());
        assertThat(entries).as("small entries share batches").anyMatch(e -> e.batchSize() > 50);
        assertThat(entries.stream().filter(e -> e.rawLen() > R7fJournalTestAccess.stageSize()))
                .as("an entry larger than a stage is a batch of its own")
                .hasSize(8)
                .allMatch(e -> e.batchSize() == 1);

        final CollectingSink sink = tail(compressed);
        assertThat(sink.isClean()).as("sink: %s", sink).isTrue();
        for (int i = 0; i < 400; i++)
        {
            assertThat(CollectingSink.concat(sink.completed.get("x-" + i).getRequestBodyFragments()))
                    .as("x-%d", i).isEqualTo(body(i));
        }

        final long compressedEnd = header(segment).getLong(R7fConstants.PREAMBLE_OFF_DATA_END);
        final long plainEnd = header(onlySealedSegment(plain)).getLong(R7fConstants.PREAMBLE_OFF_DATA_END);
        assertThat(compressedEnd).as("compressed %d bytes, plain %d", compressedEnd, plainEnd).isLessThan(plainEnd / 4);
    }

    /**
     * Rotation restarts the Sequence, so the writer stamps a batch's Sequences only once it
     * knows which segment the batch lands in. Every segment starts at 1 and runs without a gap,
     * and nothing is lost across the rotations.
     */
    @Test
    void everySegmentsBatchesStartAtSequenceOne() throws IOException
    {
        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, R7fJournalProvider.MIN_SEGMENT_SIZE, false, 1)))
        {
            for (int i = 0; i < 3000; i++)
            {
                exchange(journal, "x-" + i, body(i % 49));
            }
        }
        final List<Path> segments;
        try (Stream<Path> files = Files.list(journalDir))
        {
            segments = files.filter(p -> p.toString().endsWith(R7fConstants.R7F_FILE_EXTENSION)).toList();
        }
        assertThat(segments).as("rotated at least twice").hasSizeGreaterThanOrEqualTo(3);
        long total = 0;
        for (final Path segment : segments)
        {
            final List<EntryRef> entries = R7fTestFraming.entriesOf(segment);
            assertThat(entries).extracting(EntryRef::sequence).as("%s", segment.getFileName())
                    .isEqualTo(IntStream.rangeClosed(1, entries.size()).boxed().toList());
            assertThat(header(segment).getLong(R7fConstants.PREAMBLE_OFF_ENTRY_COUNT)).isEqualTo(entries.size());
            total += entries.size();
        }
        assertThat(total).isEqualTo(9000);

        final CollectingSink sink = tail(journalDir);
        assertThat(sink.isClean()).as("sink: %s", sink).isTrue();
        assertThat(sink.deliveries).hasSize(3000);
    }

    /**
     * Invariant 1 for a batch: staged entries are not in the segment, and a placed batch is
     * there whole. A tailer reading between the two sees nothing of the staged ones.
     */
    @Test
    void stagedEntriesAreInvisibleUntilTheirBatchIsPlaced() throws IOException
    {
        final CollectingSink sink = new CollectingSink();
        final R7Tailer tailer = new R7Tailer(journalDir, sink, sink, ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1)));
        try (R7fJournal journal = journal(journalDir, 1))
        {
            exchange(journal, "x-0", body(0));
            journal.flush();
            exchange(journal, "x-1", body(1));
            tailer.runTick();
            assertThat(sink.deliveries).as("x-1 is still staged").containsExactly("x-0");

            journal.flush();
            tailer.runTick();
            assertThat(sink.deliveries).containsExactly("x-0", "x-1");
        }
        assertThat(sink.isClean()).as("sink: %s", sink).isTrue();
    }

    /**
     * A stage that never fills is placed after the flush interval, so a quiet gateway's last
     * exchange reaches the tailer without anyone flushing or closing.
     */
    @Test
    void aStageThatIsNotFullIsPlacedAfterTheFlushInterval() throws IOException, InterruptedException
    {
        final CollectingSink sink = new CollectingSink();
        final R7Tailer tailer = new R7Tailer(journalDir, sink, sink, ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1)));
        try (R7fJournal journal = journal(journalDir, 1))
        {
            exchange(journal, "quiet", body(0));
            final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (sink.deliveries.isEmpty() && System.nanoTime() < deadline)
            {
                tailer.awaitNewData(Duration.ofMillis(100));
                tailer.runTick();
            }
            assertThat(sink.deliveries).containsExactly("quiet");
        }
    }

    /**
     * A tailer resumes from a checkpoint inside a batch when the consumer refused an entry in
     * it: the batch's offset and the Sequence to deliver next. The retry must deliver the
     * refused exchange and everything after it once, and nothing before it again.
     */
    @Test
    void aRefusalInsideABatchResumesAtTheRefusedEntry() throws IOException
    {
        try (R7fJournal journal = journal(journalDir, 1))
        {
            for (int i = 0; i < 20; i++)
            {
                exchange(journal, "x-" + i, body(i));
            }
        }
        assertThat(R7fTestFraming.entriesOf(onlySealedSegment(journalDir))).extracting(EntryRef::batchSize)
                .as("all in one batch").containsOnly(60);

        final List<String> delivered = new ArrayList<>();
        final boolean[] refusing = {true};
        final CollectingSink integrity = new CollectingSink();
        final ExchangeCompletionListener sink = new ExchangeCompletionListener()
        {
            @Override
            public void onComplete(final JournalExchange exchange)
            {
                if (refusing[0] && exchange.getRequestId().equals("x-7"))
                {
                    throw new IllegalStateException("sink unavailable");
                }
                delivered.add(exchange.getRequestId());
            }
        };
        final R7Tailer tailer = new R7Tailer(journalDir, sink, integrity, ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1)));
        tailer.runTick();
        assertThat(delivered).hasSize(7);
        assertThat(integrity.deliveryStalls).hasSize(1);

        refusing[0] = false;
        tailer.runTick();
        assertThat(delivered).as("x-7 once, and nothing before it twice")
                .isEqualTo(IntStream.range(0, 20).mapToObj(i -> "x-" + i).toList());
        assertThat(integrity.sequenceRegressions).isEmpty();
        assertThat(integrity.missingEntries).isZero();
    }

    /**
     * Damage to a batch costs the batch and, as with any damage, the rest of its block: the
     * reader resumes at the next boundary, and the Sequences after it say how many were lost.
     */
    @Test
    void damageToABatchCostsTheRestOfItsBlock() throws IOException
    {
        try (R7fJournal journal = journal(journalDir, 1))
        {
            for (int i = 0; i < 6000; i++)
            {
                exchange(journal, "x-" + i, ("body-" + i).getBytes(StandardCharsets.ISO_8859_1));
            }
        }
        final Path segment = onlySealedSegment(journalDir);
        final List<EntryRef> entries = R7fTestFraming.entriesOf(segment);
        final EntryRef victim = entries.stream()
                .filter(e -> e.offset() / BLOCK == 1 && e.fragments().size() == 1)
                .findFirst().orElseThrow();
        final int nextBoundary = (victim.offset() / BLOCK + 1) * BLOCK;
        assertThat(nextBoundary).as("entries beyond the damaged block").isLessThan(entries.getLast().offset());
        final long lost = entries.stream().filter(e -> e.offset() >= victim.offset() && e.offset() < nextBoundary).count();
        assertThat(lost).as("at least the victim's batch").isGreaterThanOrEqualTo(victim.batchSize());

        final byte[] bytes = Files.readAllBytes(segment);
        bytes[victim.payloadOffset() + 20] ^= (byte) 0xFF;
        Files.write(segment, bytes);

        final CollectingSink sink = tail(journalDir);
        assertThat(sink.corruptRegions).as("sink: %s", sink).hasSize(1);
        assertThat(sink.missingEntries).isEqualTo(lost);
        assertThat(sink.completed).containsKeys("x-0", "x-5999");
    }

    /**
     * A batch whose magic was never stamped is the ordinary tail of a crash: recovery seals
     * before it and reports nothing.
     */
    @Test
    void recoverySealsBeforeAnUncommittedBatch() throws IOException
    {
        try (R7fJournal journal = journal(journalDir, 1))
        {
            for (int i = 0; i < 40; i++)
            {
                exchange(journal, "x-" + i, body(i));
                if (i % 10 == 9)
                {
                    journal.flush();
                }
            }
        }
        final Path sealed = onlySealedSegment(journalDir);
        final List<EntryRef> entries = R7fTestFraming.entriesOf(sealed);
        final EntryRef last = entries.getLast();
        assertThat(last.batchSize()).as("the last batch").isEqualTo(30);

        final byte[] active = Files.readAllBytes(sealed);
        Arrays.fill(active, last.offset(), last.offset() + Integer.BYTES, (byte) 0);
        final String[] parts = sealed.getFileName().toString().replace(R7fConstants.R7F_FILE_EXTENSION, "").split("-");
        Files.delete(sealed);
        Files.write(journalDir.resolve(String.join("-", parts[0], parts[1], parts[2], parts[3]) + R7fConstants.ACTIVE_FILE_EXTENSION), active);

        final CollectingSink recovery = new CollectingSink();
        R7fRecoveryManager.cleanAndRecover(journalDir, recovery);
        assertThat(recovery.corruptRegions).as("sink: %s", recovery).isEmpty();
        assertThat(recovery.quarantined).isEmpty();
        final ByteBuffer recovered = header(onlySealedSegment(journalDir));
        assertThat(recovered.getLong(R7fConstants.PREAMBLE_OFF_DATA_END)).isEqualTo(last.offset());
        assertThat(recovered.getLong(R7fConstants.PREAMBLE_OFF_ENTRY_COUNT)).isEqualTo(entries.size() - last.batchSize());
        assertThat(recovered.getInt(R7fConstants.PREAMBLE_OFF_LAST_SEQUENCE)).isEqualTo(last.sequence() - last.batchIndex() - 1);

        final CollectingSink sink = tail(journalDir);
        assertThat(sink.missingEntries).as("sink: %s", sink).isZero();
        assertThat(sink.corruptRegions).isEmpty();
        assertThat(sink.deliveries).hasSize(30);
    }

    /**
     * A codec this build does not know is refused, not decoded as whatever it resembles, and
     * the file is set aside rather than deleted. Codec 1, the per-entry stream an older gateway
     * wrote, is one of them.
     */
    @ParameterizedTest
    @ValueSource(shorts = {1, 7})
    void anUnknownCodecIsSetAside(final short codec) throws IOException
    {
        try (R7fJournal journal = journal(journalDir, 1))
        {
            exchange(journal, "x", body(1));
        }
        final Path segment = onlySealedSegment(journalDir);
        final byte[] bytes = Files.readAllBytes(segment);
        ByteBuffer.wrap(bytes).putShort(R7fConstants.PREAMBLE_OFF_CODEC, codec);
        Files.write(segment, bytes);

        final CollectingSink sink = tail(journalDir);
        assertThat(sink.quarantined).as("sink: %s", sink).hasSize(1);
        assertThat(sink.quarantined.getFirst()).contains("codec " + codec);
    }

    /**
     * A writer that cannot place a batch stops the shard: later entries are refused, so their
     * requests fail closed instead of being journaled into nothing, and the failure is there
     * for gateway health to report.
     */
    @Test
    void aWriterThatCannotPlaceABatchStopsTheShard() throws IOException
    {
        final R7fJournal journal = journal(journalDir, 1);
        try
        {
            assertThat(journal.failure()).isNull();
            R7fJournalTestAccess.breakSegment(journal);
            exchange(journal, "lost", body(0));
            assertThatThrownBy(journal::flush).isInstanceOf(IllegalStateException.class).hasMessageContaining("failed");
            assertThat(journal.failure()).isNotNull();
            assertThatThrownBy(() -> exchange(journal, "refused", body(1)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("failed");
        }
        finally
        {
            try
            {
                journal.close();
            }
            catch (final IOException | RuntimeException e)
            {
                // The segment was broken on purpose
            }
        }
    }

    /* ---------- helpers ---------- */

    /**
     * 400 exchanges, mostly small with similar bodies, and every 50th one larger than a block.
     */
    private static void writeMixed(final Path dir, final int level)
    {
        try (R7fJournal journal = journal(dir, level))
        {
            for (int i = 0; i < 400; i++)
            {
                exchange(journal, "x-" + i, body(i));
            }
        }
        catch (final IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static byte[] body(final int i)
    {
        if (i % 50 == 49)
        {
            // Larger than a stage, which makes it a batch of its own whatever it compresses to
            final StringBuilder large = new StringBuilder();
            for (int n = 0; large.length() < 2 * BLOCK; n++)
            {
                large.append("{\"row\":").append(n).append(",\"of\":").append(i).append("}\n");
            }
            return large.toString().getBytes(StandardCharsets.ISO_8859_1);
        }
        return ("{\"id\":" + i + ",\"name\":\"user-" + i + "\",\"tags\":[\"a\",\"b\",\"c\"],\"active\":true}")
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static R7fJournal journal(final Path dir, final int level)
    {
        return new R7fJournal(new R7fJournalProvider(dir, 0, SEGMENT_SIZE, false, level));
    }

    private static void exchange(final R7fJournal journal, final String reqId, final byte[] body)
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.set("user-agent", "Mozilla/5.0 (X11; Linux x86_64) Gecko/20100101 Firefox/131.0");
        headers.set("accept", "application/json");
        journal.clientRequest(JournalLevel.FULL, reqId, ByteBuffer.wrap(("POST /" + reqId + " HTTP/1.1").getBytes(StandardCharsets.ISO_8859_1)),
                headers, InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        journal.requestBody(reqId, ByteBuffer.wrap(body));
        journal.endExchange(reqId, new FastGatewayAttributes(), 1L, 2L, 200, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);
    }

    private static CollectingSink tail(final Path dir) throws IOException
    {
        final CollectingSink sink = new CollectingSink();
        new R7Tailer(dir, sink, sink, ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1))).runTick();
        return sink;
    }

    private static ByteBuffer header(final Path segment) throws IOException
    {
        return ByteBuffer.wrap(Files.readAllBytes(segment)).order(ByteOrder.BIG_ENDIAN);
    }

    private static Path onlySealedSegment(final Path dir) throws IOException
    {
        try (Stream<Path> files = Files.list(dir))
        {
            final List<Path> sealed = files.filter(p -> p.toString().endsWith(R7fConstants.R7F_FILE_EXTENSION)).toList();
            assertThat(sealed).as("expected exactly one sealed segment in %s", dir).hasSize(1);
            return sealed.getFirst();
        }
    }
}
