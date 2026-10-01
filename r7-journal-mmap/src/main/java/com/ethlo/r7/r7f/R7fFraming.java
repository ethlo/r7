package com.ethlo.r7.r7f;

/**
 * Block arithmetic for format version 2 (FORMAT.md 3.1 and 4.3), shared by the writer and
 * every reader.
 * <p>
 * One copy on purpose. Padding is never marked in the file; writer and reader agree on it by
 * computing the same positions from the same numbers. If the writer and a reader carried
 * their own versions of this arithmetic, a disagreement would surface as a reader treating
 * padding as a fragment, or a fragment as padding, which reads as damage.
 */
final class R7fFraming
{
    private R7fFraming()
    {
    }

    static boolean isValidBlockSize(final long blockSize)
    {
        return blockSize >= R7fConstants.MIN_BLOCK_SIZE
                && blockSize <= R7fConstants.MAX_BLOCK_SIZE
                && (blockSize & (blockSize - 1)) == 0;
    }

    /**
     * The offset of the first byte of the next block, or of the block after this one when
     * {@code position} is itself a boundary.
     */
    static long nextBoundary(final long position, final int blockSize)
    {
        return (position & -blockSize) + blockSize;
    }

    /**
     * Where an entry placed at {@code position} actually starts: the same place, or the next
     * block when fewer than a header and one data byte remain in this one.
     */
    static long entryStart(final long position, final int blockSize)
    {
        final long remaining = nextBoundary(position, blockSize) - position;
        return remaining < R7fConstants.MIN_FRAGMENT_SIZE ? position + remaining : position;
    }

    /**
     * Data bytes the FULL or FIRST fragment at {@code start} can carry: the rest of its block,
     * less the header. At least one, because {@code start} came from {@link #entryStart}.
     */
    static long firstCapacity(final long start, final int blockSize)
    {
        return nextBoundary(start, blockSize) - start - R7fConstants.FRAGMENT_HEADER_SIZE;
    }

    /**
     * Data bytes a MIDDLE fragment carries, and the most a LAST one can.
     */
    static int continuationCapacity(final int blockSize)
    {
        return blockSize - R7fConstants.FRAGMENT_HEADER_SIZE;
    }

    /**
     * The offset one past the last fragment of an entry of {@code contentLength} bytes that
     * starts at {@code start} (already passed through {@link #entryStart}).
     */
    static long entryEnd(final long start, final long contentLength, final int blockSize)
    {
        final long first = firstCapacity(start, blockSize);
        if (contentLength <= first)
        {
            return start + R7fConstants.FRAGMENT_HEADER_SIZE + contentLength;
        }
        final long rest = contentLength - first;
        final long perBlock = continuationCapacity(blockSize);
        final long continuations = (rest + perBlock - 1) / perBlock;
        final long last = rest - (continuations - 1) * perBlock;
        return nextBoundary(start, blockSize) + (continuations - 1) * blockSize
                + R7fConstants.FRAGMENT_HEADER_SIZE + last;
    }
}
