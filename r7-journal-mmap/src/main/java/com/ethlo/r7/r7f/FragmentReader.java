package com.ethlo.r7.r7f;

import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

import com.github.luben.zstd.ZstdDecompressCtx;

/**
 * Reads format-version-2 entries out of a segment: the one place the fragment framing is
 * interpreted, shared by the decoder and by recovery so that the two cannot disagree about
 * what is an entry.
 * <p>
 * Positions are file offsets, so the buffer handed in must start at the file's first byte;
 * block boundaries are only meaningful relative to that. Nothing here reads past
 * {@code limit}, which a caller sets to Data End for a sealed segment.
 * <p>
 * What this class guarantees is FORMAT.md 6.1: it interprets bytes as a fragment header only
 * at a position reached from an entry start through verified lengths, or at a block boundary
 * ({@link #resync}). It has no way to look for a magic anywhere else, so payload bytes are
 * never read as framing.
 * <p>
 * Not thread-safe, and holds a reassembly buffer that is reused for every split entry: an
 * entry read from it is valid until the next {@link #read}. Listeners copy what they keep, as
 * they already must for the mapped segment.
 */
final class FragmentReader
{
    enum Status
    {
        /** A committed entry, verified; its fields are available until the next read. */
        ENTRY,
        /** Not even a fragment header and one byte fit before the limit. */
        END,
        /** A zero where a magic belongs: nothing was committed here. */
        UNWRITTEN,
        /** Something is here, and it is not a valid entry; {@link #problem()} says what. */
        DAMAGED
    }

    private final ByteBuffer file;
    private final ByteBuffer crcView;
    private final long limit;
    private final int blockSize;
    private final short codec;
    private final CRC32C crc = new CRC32C();

    // zstd state (FORMAT.md 4.4), created on the first compressed entry
    private final ByteBuffer streamSource;
    private ZstdDecompressCtx zstd;
    private ByteBuffer inflated;
    /** Where the next stream fragment must start for the decompressor's context to fit it. */
    private long streamNext = -1;

    private ByteBuffer assembly;

    private long entryStart;
    private long entryEnd;
    private byte fragmentType;
    private String problem;
    private int sequence;
    private ByteBuffer fbSlice;
    private ByteBuffer rawSlice;

    /**
     * @param file      the segment, index 0 being the file's first byte; not modified
     * @param limit     the offset no read may reach: Data End for a sealed segment, the file
     *                  size for an active one
     * @param blockSize from the segment's preamble, already validated
     */
    FragmentReader(final ByteBuffer file, final long limit, final int blockSize)
    {
        this(file, limit, blockSize, R7fConstants.CODEC_NONE);
    }

    FragmentReader(final ByteBuffer file, final long limit, final int blockSize, final short codec)
    {
        if (!R7fFraming.isValidBlockSize(blockSize))
        {
            throw new IllegalArgumentException("Invalid block size " + blockSize);
        }
        this.file = file.duplicate().order(ByteOrder.BIG_ENDIAN);
        this.crcView = file.duplicate();
        this.limit = Math.min(limit, file.capacity());
        this.blockSize = blockSize;
        this.codec = codec;
        this.streamSource = file.duplicate();
    }

    /**
     * Reads the entry an entry boundary at {@code position} leads to, skipping the padding at
     * the end of a block first.
     */
    Status read(final long position)
    {
        fbSlice = null;
        rawSlice = null;
        problem = null;

        final long start = R7fFraming.entryStart(position, blockSize);
        entryStart = start;
        if (start + R7fConstants.MIN_FRAGMENT_SIZE > limit)
        {
            return Status.END;
        }

        final int magic = file.getInt((int) start);
        if (magic == 0)
        {
            problem = "unwritten region";
            return Status.UNWRITTEN;
        }
        if (magic != R7fConstants.FRAGMENT_MAGIC)
        {
            return damaged(String.format("bad fragment magic 0x%08X", magic));
        }

        // The writer stamps this magic last, behind a release fence, and for a split entry
        // only after every continuation is complete (FORMAT.md 5.1). Pairing an acquire here
        // is what makes all of it visible to this reader, which in production is a different
        // process sharing the mapping.
        VarHandle.acquireFence();

        final long firstLength = checkFragment(start);
        if (firstLength < 0)
        {
            return Status.DAMAGED;
        }

        final byte flags = file.get((int) (start + R7fConstants.FRAGMENT_OFF_FLAGS));
        if (codec == R7fConstants.CODEC_ZSTD && fragmentType == R7fConstants.FRAGMENT_FULL
                && flags == R7fConstants.FLAG_PAD)
        {
            // Not an entry: the writer closed the block off here
            return read(start + R7fConstants.FRAGMENT_HEADER_SIZE + firstLength);
        }

        final ByteBuffer content;
        final int contentBase;
        final long contentLength;

        if (fragmentType == R7fConstants.FRAGMENT_FULL)
        {
            content = file;
            contentBase = (int) (start + R7fConstants.FRAGMENT_HEADER_SIZE);
            contentLength = firstLength;
            entryEnd = start + R7fConstants.FRAGMENT_HEADER_SIZE + firstLength;
        }
        else if (fragmentType == R7fConstants.FRAGMENT_FIRST)
        {
            contentLength = verifyContinuations(start, firstLength);
            if (contentLength < 0)
            {
                return Status.DAMAGED;
            }
            content = assemble(start, firstLength, contentLength);
            contentBase = 0;
        }
        else
        {
            // Only reachable after damage: an entry boundary that lands on a continuation was
            // reached by a resync, and resync steps over these itself.
            return damaged("continuation fragment where an entry should start");
        }

        if (codec == R7fConstants.CODEC_ZSTD)
        {
            final long produced = (flags & R7fConstants.FLAG_STANDALONE) != 0
                    ? inflateStandalone(content, contentBase, contentLength)
                    : inflateStream(start, flags, contentBase, contentLength);
            if (produced < 0)
            {
                return Status.DAMAGED;
            }
            return parse(inflated, 0, produced);
        }
        return parse(content, contentBase, contentLength);
    }

    /**
     * A stream fragment's entry, decompressed with the context of every earlier
     * stream fragment in its block. When this reader did not just decode the previous one —
     * it started mid-block, from a checkpoint — the context is rebuilt from the block's start.
     *
     * @return the entry's plain length, or -1 with {@link #problem} set
     */
    private long inflateStream(final long start, final byte flags, final int base, final long length)
    {
        final long expected = streamNext;
        streamNext = -1;
        ensureZstd(4L * blockSize);
        if (flags == R7fConstants.FLAG_STREAM_START)
        {
            zstd.reset();
        }
        else if (flags != R7fConstants.FLAG_STREAM_CONTINUE)
        {
            damaged("unknown fragment flags " + flags);
            return -1;
        }
        else if (expected != start && !replayStream(start))
        {
            damaged("cannot rebuild the block's compression context before offset " + start);
            return -1;
        }
        final long produced = decompressStream(base, length);
        if (produced < 0)
        {
            damaged("cannot decompress stream fragment at offset " + start);
            return -1;
        }
        streamNext = start + R7fConstants.FRAGMENT_HEADER_SIZE + length;
        return produced;
    }

    /**
     * Feeds every stream fragment from the start of {@code target}'s block up to it through the
     * decompressor, output discarded.
     */
    private boolean replayStream(final long target)
    {
        long at = Math.max(target & -blockSize, R7fConstants.PREAMBLE_SIZE);
        boolean started = false;
        while (at < target)
        {
            if (file.getInt((int) at) != R7fConstants.FRAGMENT_MAGIC)
            {
                return false;
            }
            final long length = checkFragment(at);
            if (length < 0)
            {
                return false;
            }
            final byte flags = file.get((int) (at + R7fConstants.FRAGMENT_OFF_FLAGS));
            if (fragmentType == R7fConstants.FRAGMENT_FULL
                    && (flags == R7fConstants.FLAG_STREAM_START || flags == R7fConstants.FLAG_STREAM_CONTINUE))
            {
                if (flags == R7fConstants.FLAG_STREAM_START)
                {
                    zstd.reset();
                    started = true;
                }
                if (!started || decompressStream((int) (at + R7fConstants.FRAGMENT_HEADER_SIZE), length) < 0)
                {
                    return false;
                }
            }
            at += R7fConstants.FRAGMENT_HEADER_SIZE + length;
        }
        return started && at == target;
    }

    /**
     * Decompresses one flushed piece of the block's stream into {@link #inflated}.
     */
    private long decompressStream(final int base, final long length)
    {
        inflated.clear();
        streamSource.clear();
        streamSource.limit((int) (base + length)).position(base);
        try
        {
            while (true)
            {
                final int consumed = streamSource.position();
                final int produced = inflated.position();
                zstd.decompressDirectByteBufferStream(inflated, streamSource);
                if (streamSource.position() == consumed && inflated.position() == produced)
                {
                    break;
                }
            }
        }
        catch (final RuntimeException e)
        {
            return -1;
        }
        return streamSource.hasRemaining() ? -1 : inflated.position();
    }

    /**
     * {@code plainLen(4)} then a zstd frame of its own.
     */
    private long inflateStandalone(final ByteBuffer content, final int base, final long length)
    {
        if (length < Integer.BYTES + 1)
        {
            damaged("standalone entry too short");
            return -1;
        }
        final int plainLength = content.getInt(base);
        ensureZstd(Math.max(plainLength, 4L * blockSize));
        try
        {
            final int produced = zstd.decompressDirectByteBuffer(inflated, 0, plainLength, content,
                    base + Integer.BYTES, (int) (length - Integer.BYTES));
            if (produced == plainLength)
            {
                return produced;
            }
        }
        catch (final RuntimeException e)
        {
            // reported below
        }
        damaged("cannot decompress standalone entry");
        return -1;
    }

    private void ensureZstd(final long capacity)
    {
        if (zstd == null)
        {
            zstd = new ZstdDecompressCtx();
        }
        if (inflated == null || inflated.capacity() < capacity)
        {
            inflated = ByteBuffer.allocateDirect((int) capacity);
        }
    }

    /**
     * The entry's plain content: {@code sequence fbLen rawLen fb raw}.
     */
    private Status parse(final ByteBuffer content, final int contentBase, final long contentLength)
    {
        if (contentLength < R7fConstants.ENTRY_CONTENT_HEADER_SIZE)
        {
            return damaged("entry too short to hold its header (" + contentLength + " bytes)");
        }

        final int sequenceValue = content.getInt(contentBase);
        final int fbLen = content.getInt(contentBase + Integer.BYTES);
        final int rawLen = content.getInt(contentBase + 2 * Integer.BYTES);
        if (fbLen < 0 || rawLen < 0
                || R7fConstants.ENTRY_CONTENT_HEADER_SIZE + (long) fbLen + rawLen != contentLength)
        {
            return damaged("inconsistent entry lengths (content=" + contentLength + ", fbLen=" + fbLen + ", rawLen=" + rawLen + ")");
        }

        sequence = sequenceValue;
        final int fbStart = contentBase + R7fConstants.ENTRY_CONTENT_HEADER_SIZE;
        fbSlice = content.slice(fbStart, fbLen).order(ByteOrder.LITTLE_ENDIAN);
        rawSlice = rawLen > 0 ? content.slice(fbStart + fbLen, rawLen) : null;
        return Status.ENTRY;
    }

    /**
     * Where to look for the next entry after damage at {@code damagedAt}, in a segment whose
     * bytes are final: the first block boundary past it that starts an entry, or the end of an
     * orphaned LAST fragment found at a boundary, or {@code limit} when nothing remains.
     * <p>
     * Only boundaries are examined, because only they are guaranteed to hold a header the
     * writer placed (FORMAT.md 3.1). The rest of the damaged block is given up even where
     * entries in it are intact; that is the price of never reading payload as framing.
     */
    long resync(final long damagedAt)
    {
        for (long boundary = R7fFraming.nextBoundary(damagedAt, blockSize);
             boundary + R7fConstants.MIN_FRAGMENT_SIZE <= limit;
             boundary += blockSize)
        {
            if (file.getInt((int) boundary) != R7fConstants.FRAGMENT_MAGIC)
            {
                continue;
            }
            final long length = checkFragment(boundary);
            if (length < 0)
            {
                continue;
            }
            switch (fragmentType)
            {
                case R7fConstants.FRAGMENT_FULL, R7fConstants.FRAGMENT_FIRST ->
                {
                    return boundary;
                }
                case R7fConstants.FRAGMENT_LAST ->
                {
                    // The tail of an entry whose start was lost. What follows it was placed by
                    // the writer, so reading can resume right after it.
                    return boundary + R7fConstants.FRAGMENT_HEADER_SIZE + length;
                }
                default ->
                {
                    // A MIDDLE fills its block; the next candidate is the next boundary
                }
            }
        }
        return limit;
    }

    /**
     * Checks the continuations of the split entry whose FIRST fragment is at {@code start}.
     *
     * @return the entry's content length, or -1 with {@link #problem} set
     */
    private long verifyContinuations(final long start, final long firstLength)
    {
        long position = R7fFraming.nextBoundary(start, blockSize);
        if (start + R7fConstants.FRAGMENT_HEADER_SIZE + firstLength != position)
        {
            damaged("first fragment does not fill its block");
            return -1;
        }

        long total = firstLength;
        while (true)
        {
            if (position + R7fConstants.MIN_FRAGMENT_SIZE > limit)
            {
                damaged("split entry runs past the end of the data");
                return -1;
            }
            if (file.getInt((int) position) != R7fConstants.FRAGMENT_MAGIC)
            {
                damaged("split entry is missing its continuation at offset " + position);
                return -1;
            }
            final long length = checkFragment(position);
            if (length < 0)
            {
                problem = "continuation at offset " + position + ": " + problem;
                return -1;
            }
            total += length;
            if (fragmentType == R7fConstants.FRAGMENT_LAST)
            {
                entryEnd = position + R7fConstants.FRAGMENT_HEADER_SIZE + length;
                return total;
            }
            if (fragmentType != R7fConstants.FRAGMENT_MIDDLE)
            {
                damaged("split entry interrupted by a fragment of type " + fragmentType + " at offset " + position);
                return -1;
            }
            if (length != R7fFraming.continuationCapacity(blockSize))
            {
                damaged("middle fragment at offset " + position + " does not fill its block");
                return -1;
            }
            position += blockSize;
        }
    }

    /**
     * Copies a verified split entry's data into the reassembly buffer, which grows to the
     * largest entry seen and is then reused.
     */
    private ByteBuffer assemble(final long start, final long firstLength, final long contentLength)
    {
        if (assembly == null || assembly.capacity() < contentLength)
        {
            final int capacity = (int) Math.max(contentLength, 2L * blockSize);
            // Direct when compressed: zstd-jni decompresses from direct buffers only
            assembly = (codec == R7fConstants.CODEC_NONE ? ByteBuffer.allocate(capacity) : ByteBuffer.allocateDirect(capacity))
                    .order(ByteOrder.BIG_ENDIAN);
        }
        assembly.clear();
        final int perBlock = R7fFraming.continuationCapacity(blockSize);

        assembly.put(0, file, (int) (start + R7fConstants.FRAGMENT_HEADER_SIZE), (int) firstLength);
        long copied = firstLength;
        long position = R7fFraming.nextBoundary(start, blockSize);
        while (copied < contentLength)
        {
            final int length = (int) Math.min(perBlock, contentLength - copied);
            assembly.put((int) copied, file, (int) (position + R7fConstants.FRAGMENT_HEADER_SIZE), length);
            copied += length;
            position += blockSize;
        }
        return assembly;
    }

    /**
     * Validates the fragment header at {@code position} and its CRC.
     *
     * @return its data length, with {@link #fragmentType} set, or -1 with {@link #problem} set
     */
    private long checkFragment(final long position)
    {
        final byte type = file.get((int) (position + R7fConstants.FRAGMENT_OFF_TYPE));
        if (type < R7fConstants.FRAGMENT_FULL || type > R7fConstants.FRAGMENT_LAST)
        {
            damaged("unknown fragment type " + type);
            return -1;
        }

        final long length = Integer.toUnsignedLong(file.getInt((int) (position + R7fConstants.FRAGMENT_OFF_LENGTH)));
        final long dataStart = position + R7fConstants.FRAGMENT_HEADER_SIZE;
        if (length < 1 || dataStart + length > R7fFraming.nextBoundary(position, blockSize))
        {
            damaged("fragment length " + length + " does not fit its block");
            return -1;
        }
        if (dataStart + length > limit)
        {
            damaged("fragment runs past the end of the data");
            return -1;
        }

        crc.reset();
        crcView.clear();
        crcView.limit((int) (position + R7fConstants.FRAGMENT_OFF_CRC)).position((int) (position + R7fConstants.FRAGMENT_OFF_TYPE));
        crc.update(crcView);
        crcView.clear();
        crcView.limit((int) (dataStart + length)).position((int) dataStart);
        crc.update(crcView);

        if ((int) crc.getValue() != file.getInt((int) (position + R7fConstants.FRAGMENT_OFF_CRC)))
        {
            damaged("checksum mismatch");
            return -1;
        }

        fragmentType = type;
        return length;
    }

    private Status damaged(final String description)
    {
        problem = description;
        return Status.DAMAGED;
    }

    /** Where the entry, or the damage, starts once padding is skipped. */
    long entryStart()
    {
        return entryStart;
    }

    /** One past the entry's last fragment. Valid after {@link Status#ENTRY}. */
    long entryEnd()
    {
        return entryEnd;
    }

    int sequence()
    {
        return sequence;
    }

    /** The FlatBuffers payload, little-endian. */
    ByteBuffer fbSlice()
    {
        return fbSlice;
    }

    /** The raw payload, or null when the entry has none. */
    ByteBuffer rawSlice()
    {
        return rawSlice;
    }

    String problem()
    {
        return problem;
    }
}
