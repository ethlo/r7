package com.ethlo.r7;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.ethlo.r7.r7f.R7fConstants;

/**
 * Properties that must hold of a journal directory whatever the test did to it.
 * <p>
 * The tests were asserting outcomes — this many exchanges, these values — and passing
 * while the invariants those outcomes depend on were quietly broken. Two bugs even
 * cancelled each other's symptoms: the clean-close path sealed segments without recording
 * where their data ended, and the reader stopped at the resulting zero tail without
 * reporting it. Either alone would have been visible; together they looked like success.
 * <p>
 * These checks are the safety net for that class of mistake. Call them after anything that
 * writes, seals or recovers a journal.
 */
final class JournalInvariants
{
    private JournalInvariants()
    {
    }

    /**
     * Every sealed segment ends where its seal record says it does.
     * <p>
     * This replaces an older check that compared the last entry's end against the file's
     * size, which required every seal to truncate. The extent is recorded now, so the
     * pre-allocated tail always remains — and the check is stronger for it, because it
     * compares a declared fact against the framing instead of two derived quantities
     * against each other.
     */
    static void assertSealedSegmentsEndWhereTheySay(final Path journalDir) throws IOException
    {
        for (final Path segment : segments(journalDir, R7fConstants.R7F_FILE_EXTENSION))
        {
            final ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(segment)).order(ByteOrder.BIG_ENDIAN);
            final String name = segment.getFileName().toString();

            assertThat(buffer.getInt(R7fConstants.PREAMBLE_OFF_SEAL_MAGIC))
                    .as("%s: a sealed segment must carry a seal record", name)
                    .isEqualTo(R7fConstants.SEAL_MAGIC);

            final long declaredEnd = buffer.getLong(R7fConstants.PREAMBLE_OFF_DATA_END);
            final long lastEntryEnd = endOfLastEntry(segment);

            assertThat(declaredEnd)
                    .as("%s: declares its data ends at %d, but the framing ends at %d",
                            name, declaredEnd, lastEntryEnd)
                    .isEqualTo(lastEntryEnd);
            assertThat(declaredEnd)
                    .as("%s: declares data past the end of the file (%d bytes)", name, buffer.limit())
                    .isLessThanOrEqualTo(buffer.limit());
        }
    }

    /**
     * Every segment carries its own (shard, sequence) identity. The tailer deduplicates on
     * that key, so a collision means one of the two files is silently never read.
     */
    static void assertSegmentKeysAreUnique(final Path journalDir) throws IOException
    {
        final List<String> keys = new ArrayList<>();
        try (Stream<Path> files = Files.list(journalDir))
        {
            for (final Path file : files.toList())
            {
                final String[] parts = file.getFileName().toString().split("-");
                if (parts.length >= 4 && "shard".equals(parts[0]))
                {
                    keys.add(parts[1] + "-" + parts[3].split("\\.")[0]);
                }
            }
        }

        assertThat(keys).doesNotHaveDuplicates();
    }

    /**
     * Every sealed segment carries a seal record, and it describes what the segment actually
     * holds.
     * <p>
     * This is the invariant that makes the others checkable without a full decode: a reader
     * can compare its own count and last sequence against the file's own account. It only
     * means something if the account is right, so it is verified here against a direct walk
     * of the framing.
     * <p>
     * The writer always seals with count equal to last sequence. Recovery may seal a lossy
     * segment, where the count is lower — so the count is checked against the entries found,
     * not against the sequence.
     */
    static void assertSealedSegmentsDescribeThemselves(final Path journalDir) throws IOException
    {
        for (final Path segment : segments(journalDir, R7fConstants.R7F_FILE_EXTENSION))
        {
            final ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(segment)).order(ByteOrder.BIG_ENDIAN);
            final String name = segment.getFileName().toString();

            assertThat(buffer.limit())
                    .as("%s: too short to hold a preamble", name)
                    .isGreaterThanOrEqualTo(R7fConstants.PREAMBLE_SIZE);

            assertThat(buffer.getInt(R7fConstants.PREAMBLE_OFF_SEAL_MAGIC))
                    .as("%s: a sealed segment must say so in its bytes, not only in its name", name)
                    .isEqualTo(R7fConstants.SEAL_MAGIC);

            final long recordedCount = buffer.getLong(R7fConstants.PREAMBLE_OFF_ENTRY_COUNT);
            final int recordedLast = buffer.getInt(R7fConstants.PREAMBLE_OFF_LAST_SEQUENCE);

            final List<Integer> sequences = entrySequences(buffer);
            assertThat(recordedCount)
                    .as("%s: seal record claims %d entries, the framing holds %d", name, recordedCount, sequences.size())
                    .isEqualTo(sequences.size());

            if (!sequences.isEmpty())
            {
                assertThat(recordedLast)
                        .as("%s: seal record claims last entry #%d, the framing ends at #%d",
                                name, recordedLast, sequences.getLast())
                        .isEqualTo(sequences.getLast());
            }
        }
    }

    /**
     * The sequence number of every structurally valid entry, in file order. Walks the framing
     * directly, for the same reason {@link #endOfLastEntry} does.
     */
    private static List<Integer> entrySequences(final ByteBuffer buffer)
    {
        final List<Integer> sequences = new ArrayList<>();
        int position = R7fConstants.PREAMBLE_SIZE;

        while (position + R7fConstants.MIN_ENTRY_SIZE <= buffer.limit())
        {
            if (buffer.getInt(position) != R7fConstants.MAGIC)
            {
                break;
            }
            final int fbLen = buffer.getInt(position + 12);
            final int rawLen = buffer.getInt(position + 16);
            if (fbLen < 0 || rawLen < 0)
            {
                break;
            }
            final long end = position + (long) R7fConstants.ENTRY_HEADER_SIZE + fbLen + rawLen + Integer.BYTES;
            if (end > buffer.limit())
            {
                break;
            }
            sequences.add(buffer.getInt(position + Integer.BYTES));
            position = (int) end;
        }
        return sequences;
    }

    /**
     * Offset just past the last structurally valid entry, walking the framing directly.
     * Deliberately independent of the production reader: a check that used the same code
     * it is checking would agree with it about anything.
     */
    private static long endOfLastEntry(final Path segment) throws IOException
    {
        final ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(segment)).order(ByteOrder.BIG_ENDIAN);

        long position = R7fConstants.PREAMBLE_SIZE;
        long lastEnd = R7fConstants.PREAMBLE_SIZE;

        while (position + R7fConstants.MIN_ENTRY_SIZE <= buffer.limit())
        {
            if (buffer.getInt((int) position) != R7fConstants.MAGIC)
            {
                break;
            }
            final int fbLen = buffer.getInt((int) position + 12);
            final int rawLen = buffer.getInt((int) position + 16);
            if (fbLen < 0 || rawLen < 0)
            {
                break;
            }

            final long end = position + R7fConstants.ENTRY_HEADER_SIZE + fbLen + rawLen + Integer.BYTES;
            if (end > buffer.limit())
            {
                break;
            }

            position = end;
            lastEnd = end;
        }

        return lastEnd;
    }

    private static List<Path> segments(final Path dir, final String suffix) throws IOException
    {
        try (Stream<Path> files = Files.list(dir))
        {
            return files.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
        }
    }
}
