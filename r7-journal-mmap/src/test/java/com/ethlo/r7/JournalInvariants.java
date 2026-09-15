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
 * cancelled each other's symptoms: the clean-close path left a pre-allocated tail on
 * sealed segments, and the reader stopped at that tail without reporting it. Either alone
 * would have been visible; together they looked like success.
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
     * Every sealed segment ends exactly where its last entry ends.
     * <p>
     * FORMAT.md §7 requires it, and §6 depends on it: the reader treats zeroes inside a
     * sealed segment as data that never reached the device, which is only sound if sealing
     * always truncates.
     */
    static void assertSealedSegmentsAreExact(final Path journalDir) throws IOException
    {
        for (final Path segment : segments(journalDir, R7fConstants.R7F_FILE_EXTENSION))
        {
            final long size = Files.size(segment);
            final long lastEntryEnd = endOfLastEntry(segment);

            assertThat(lastEntryEnd)
                    .as("%s: sealed segments must not carry bytes beyond their last entry "
                            + "(size %d, last entry ends at %d)", segment.getFileName(), size, lastEntryEnd)
                    .isEqualTo(size);
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
