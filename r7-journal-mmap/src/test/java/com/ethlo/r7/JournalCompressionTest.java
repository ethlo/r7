package com.ethlo.r7;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Codec 1 (FORMAT.md 4.4): a zstd stream per block, every entry flushed into it as its own
 * fragment. What it must keep from the uncompressed format: every entry committed by its own
 * magic, damage bounded by the block, and readers able to start mid-block. Entry fields here
 * come from {@link R7fTestFraming}, which decodes compressed segments on its own.
 */
class JournalCompressionTest
{
    private static final long SEGMENT_SIZE = 1024L * 1024L;
    private static final int BLOCK = R7fConstants.DEFAULT_BLOCK_SIZE;

    @TempDir
    Path journalDir;

    /**
     * Every shape a compressed segment can hold — stream starts and continuations, pads where
     * the next entry might not fit, and standalone entries larger than a block — round-trips
     * byte for byte, and the result is smaller than the same journal uncompressed.
     */
    @Test
    void everyFragmentKindRoundTripsAndTheJournalShrinks() throws IOException
    {
        final Path compressed = Files.createDirectory(journalDir.resolve("zstd"));
        final Path plain = Files.createDirectory(journalDir.resolve("none"));
        writeMixed(compressed, 1);
        writeMixed(plain, 0);

        final Path segment = onlySealedSegment(compressed);
        assertThat(header(segment).getShort(R7fConstants.PREAMBLE_OFF_CODEC)).isEqualTo(R7fConstants.CODEC_ZSTD);

        final List<EntryRef> entries = R7fTestFraming.entriesOf(segment);
        assertThat(entries).extracting(EntryRef::flags)
                .contains(R7fConstants.FLAG_STREAM_START, R7fConstants.FLAG_STREAM_CONTINUE, R7fConstants.FLAG_STANDALONE);
        assertThat(R7fTestFraming.padsOf(segment)).as("blocks closed early with a PAD").isNotEmpty();
        assertThat(entries.stream().filter(e -> e.flags() == R7fConstants.FLAG_STANDALONE))
                .as("standalone is for entries too large for a block, and only those")
                .hasSize(8)
                .allMatch(e -> e.rawLen() > BLOCK);

        final CollectingSink sink = tail(compressed);
        assertThat(sink.isClean()).as("sink: %s", sink).isTrue();
        for (int i = 0; i < 400; i++)
        {
            assertThat(CollectingSink.concat(sink.completed.get("x-" + i).getRequestBodyFragments()))
                    .as("x-%d", i).isEqualTo(body(i));
        }

        final long compressedEnd = header(segment).getLong(R7fConstants.PREAMBLE_OFF_DATA_END);
        final long plainEnd = header(onlySealedSegment(plain)).getLong(R7fConstants.PREAMBLE_OFF_DATA_END);
        assertThat(compressedEnd).as("compressed %d bytes, plain %d", compressedEnd, plainEnd).isLessThan(plainEnd / 2);
    }

    /**
     * A tailer resumes from a checkpoint, which in a compressed segment is usually mid-block,
     * after fragments whose stream context it does not have. It has to rebuild that context
     * from the block's start; reading the continuation cold would fail to decompress.
     */
    @Test
    void aTailerResumingMidBlockRebuildsTheStream() throws IOException
    {
        final CollectingSink sink = new CollectingSink();
        final R7Tailer tailer = new R7Tailer(journalDir, sink, sink, ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1)));
        try (R7fJournal journal = journal(journalDir, 1))
        {
            for (int i = 0; i < 60; i++)
            {
                exchange(journal, "x-" + i, body(i));
                tailer.runTick();
            }
        }
        tailer.runTick();

        assertThat(sink.isClean()).as("sink: %s", sink).isTrue();
        for (int i = 0; i < 60; i++)
        {
            assertThat(CollectingSink.concat(sink.completed.get("x-" + i).getRequestBodyFragments())).isEqualTo(body(i));
        }
    }

    /**
     * A damaged stream fragment makes the rest of its block undecodable, and costs exactly
     * that: the reader resumes at the next boundary, where a new stream starts.
     */
    @Test
    void damageInAStreamCostsTheRestOfItsBlock() throws IOException
    {
        try (R7fJournal journal = journal(journalDir, 1))
        {
            for (int i = 0; i < 1500; i++)
            {
                exchange(journal, "x-" + i, ("body-" + i).getBytes(StandardCharsets.ISO_8859_1));
            }
        }
        final Path segment = onlySealedSegment(journalDir);
        final List<EntryRef> entries = R7fTestFraming.entriesOf(segment);
        final EntryRef victim = entries.stream()
                .filter(e -> e.flags() == R7fConstants.FLAG_STREAM_CONTINUE && e.offset() / BLOCK == 1)
                .skip(5).findFirst().orElseThrow();
        final int nextBoundary = (victim.offset() / BLOCK + 1) * BLOCK;
        assertThat(nextBoundary).as("entries beyond the damaged block").isLessThan(entries.getLast().offset());
        final long lost = entries.stream().filter(e -> e.offset() >= victim.offset() && e.offset() < nextBoundary).count();

        final byte[] bytes = Files.readAllBytes(segment);
        bytes[victim.payloadOffset() + 2] ^= (byte) 0xFF;
        Files.write(segment, bytes);

        final CollectingSink sink = tail(journalDir);
        assertThat(sink.corruptRegions).as("sink: %s", sink).hasSize(1);
        assertThat(sink.missingEntries).isEqualTo(lost);
        assertThat(sink.completed).containsKeys("x-0", "x-1499");
    }

    /**
     * An entry whose magic was never stamped is the ordinary tail of a crash, compressed or
     * not: recovery seals before it and reports nothing.
     */
    @Test
    void recoverySealsBeforeAnUncommittedCompressedEntry() throws IOException
    {
        try (R7fJournal journal = journal(journalDir, 1))
        {
            for (int i = 0; i < 50; i++)
            {
                exchange(journal, "x-" + i, body(i));
            }
        }
        final Path sealed = onlySealedSegment(journalDir);
        final List<EntryRef> entries = R7fTestFraming.entriesOf(sealed);
        final EntryRef last = entries.getLast();

        final byte[] active = Files.readAllBytes(sealed);
        Arrays.fill(active, last.offset(), last.offset() + Integer.BYTES, (byte) 0);
        final String[] parts = sealed.getFileName().toString().replace(R7fConstants.R7F_FILE_EXTENSION, "").split("-");
        Files.delete(sealed);
        Files.write(journalDir.resolve(String.join("-", parts[0], parts[1], parts[2], parts[3]) + R7fConstants.ACTIVE_FILE_EXTENSION), active);

        final CollectingSink recovery = new CollectingSink();
        R7fRecoveryManager.cleanAndRecover(journalDir, recovery);
        assertThat(recovery.corruptRegions).as("sink: %s", recovery).isEmpty();
        assertThat(recovery.quarantined).isEmpty();
        assertThat(header(onlySealedSegment(journalDir)).getLong(R7fConstants.PREAMBLE_OFF_DATA_END)).isEqualTo(last.offset());

        final CollectingSink sink = tail(journalDir);
        assertThat(sink.missingEntries).as("sink: %s", sink).isZero();
        assertThat(sink.corruptRegions).isEmpty();
    }

    /**
     * A codec this build does not know is refused, not decoded as whatever it resembles, and
     * the file is set aside rather than deleted.
     */
    @Test
    void anUnknownCodecIsSetAside() throws IOException
    {
        try (R7fJournal journal = journal(journalDir, 1))
        {
            exchange(journal, "x", body(1));
        }
        final Path segment = onlySealedSegment(journalDir);
        final byte[] bytes = Files.readAllBytes(segment);
        ByteBuffer.wrap(bytes).putShort(R7fConstants.PREAMBLE_OFF_CODEC, (short) 7);
        Files.write(segment, bytes);

        final CollectingSink sink = tail(journalDir);
        assertThat(sink.quarantined).as("sink: %s", sink).hasSize(1);
        assertThat(sink.quarantined.getFirst()).contains("codec 7");
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
            // Larger than a block, which is what sends it STANDALONE whatever it compresses to
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
