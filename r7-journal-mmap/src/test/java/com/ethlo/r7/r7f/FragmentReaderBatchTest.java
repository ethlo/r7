package com.ethlo.r7.r7f;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

import org.junit.jupiter.api.Test;

import com.github.luben.zstd.Zstd;

/**
 * A batch (FORMAT.md 4.4) is checked whole before any of its entries is yielded: either
 * every entry is delivered or the record is damaged. A batch is placed whole or not at all,
 * so a gap or a step back between its Sequences is damage, not loss.
 */
class FragmentReaderBatchTest
{
    private static final int BLOCK = R7fConstants.DEFAULT_BLOCK_SIZE;

    @Test
    void aBatchYieldsItsEntriesInOrder()
    {
        final FragmentReader reader = reader(batch(3, entry(5, 3), entry(6, 0), entry(7, 40)));
        assertThat(reader.read(R7fConstants.PREAMBLE_SIZE)).isEqualTo(FragmentReader.Status.ENTRY);
        assertThat(reader.recordLastSequence()).isEqualTo(7);

        final List<Integer> sequences = new ArrayList<>();
        final List<Integer> rawLengths = new ArrayList<>();
        do
        {
            sequences.add(reader.sequence());
            rawLengths.add(reader.rawSlice() == null ? 0 : reader.rawSlice().remaining());
        }
        while (reader.nextInRecord());
        assertThat(sequences).containsExactly(5, 6, 7);
        assertThat(rawLengths).containsExactly(3, 0, 40);
        assertThat(reader.hasMoreInRecord()).isFalse();
    }

    @Test
    void sequencesThatAreNotContiguousAreDamage()
    {
        assertDamaged(batch(2, entry(5, 3), entry(7, 3)), "Sequence 7 after 5");
        assertDamaged(batch(2, entry(5, 3), entry(4, 3)), "Sequence 4 after 5");
    }

    @Test
    void aCountThatDisagreesWithTheContentIsDamage()
    {
        assertDamaged(batch(3, entry(1, 3), entry(2, 3)), "runs past the batch");
        assertDamaged(batch(1, entry(1, 3), entry(2, 3)), "after its last entry");
        assertDamaged(batch(0, entry(1, 3)), "inconsistent batch header");
    }

    /** Rejected from the header alone, before anything is allocated for it. */
    @Test
    void aPlainLengthLargerThanTheSegmentIsDamage()
    {
        final byte[] content = batch(1, entry(1, 3));
        ByteBuffer.wrap(content).putInt(0, Integer.MAX_VALUE);
        assertDamaged(content, "inconsistent batch header");
    }

    @Test
    void anEntryWhoseLengthsRunPastTheBatchIsDamage()
    {
        final byte[] entry = entry(1, 3);
        ByteBuffer.wrap(entry).putInt(8, 1000);
        assertDamaged(batch(1, entry), "inconsistent lengths");
    }

    private static void assertDamaged(final byte[] content, final String problem)
    {
        final FragmentReader reader = reader(content);
        assertThat(reader.read(R7fConstants.PREAMBLE_SIZE)).isEqualTo(FragmentReader.Status.DAMAGED);
        assertThat(reader.problem()).contains(problem);
    }

    /** A 4.2 entry with an 8-byte stand-in for its FlatBuffer and {@code rawLen} raw bytes. */
    private static byte[] entry(final int sequence, final int rawLen)
    {
        final ByteBuffer b = ByteBuffer.allocate(R7fConstants.ENTRY_CONTENT_HEADER_SIZE + 8 + rawLen);
        b.putInt(sequence).putInt(8).putInt(rawLen);
        return b.array();
    }

    private static byte[] batch(final int count, final byte[]... entries)
    {
        int plainLength = 0;
        for (final byte[] entry : entries)
        {
            plainLength += entry.length;
        }
        final ByteBuffer plain = ByteBuffer.allocate(plainLength);
        for (final byte[] entry : entries)
        {
            plain.put(entry);
        }
        final byte[] frame = Zstd.compress(plain.array(), 1);
        return ByteBuffer.allocate(R7fConstants.BATCH_HEADER_SIZE + frame.length)
                .putInt(plainLength).putInt(count).put(frame).array();
    }

    /** A segment holding one FULL fragment with {@code content} at the first entry boundary. */
    private static FragmentReader reader(final byte[] content)
    {
        final ByteBuffer file = ByteBuffer.allocateDirect(2 * BLOCK).order(ByteOrder.BIG_ENDIAN);
        final int at = R7fConstants.PREAMBLE_SIZE;
        file.put(at + R7fConstants.FRAGMENT_OFF_TYPE, R7fConstants.FRAGMENT_FULL);
        file.putInt(at + R7fConstants.FRAGMENT_OFF_LENGTH, content.length);
        file.put(at + R7fConstants.FRAGMENT_HEADER_SIZE, content);
        final CRC32C crc = new CRC32C();
        crc.update(file.duplicate().limit(at + R7fConstants.FRAGMENT_OFF_CRC).position(at + R7fConstants.FRAGMENT_OFF_TYPE));
        crc.update(file.duplicate().limit(at + R7fConstants.FRAGMENT_HEADER_SIZE + content.length).position(at + R7fConstants.FRAGMENT_HEADER_SIZE));
        file.putInt(at + R7fConstants.FRAGMENT_OFF_CRC, (int) crc.getValue());
        file.putInt(at, R7fConstants.FRAGMENT_MAGIC);
        return new FragmentReader(file, file.capacity(), BLOCK, R7fConstants.CODEC_ZSTD_BATCH);
    }
}
