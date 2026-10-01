package com.ethlo.r7;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;

import com.ethlo.r7.r7f.R7fConstants;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDecompressCtx;

/**
 * Format-version-2 framing for tests, written from FORMAT.md rather than shared with the
 * production reader: a check that used the code it checks would agree with it about anything.
 * <p>
 * Only walks committed entries from the start of a segment and stops at the first thing that
 * is not one; it does no resynchronisation. Tests use it to find entries to damage, and to
 * repair or forge framing on purpose.
 * <p>
 * Compressed segments (codec 1, FORMAT.md 4.4) are decoded here with zstd directly, one
 * stream per block, so the entry fields it reports are the plain ones either way.
 */
final class R7fTestFraming
{
    static final int HEADER = 16;

    private R7fTestFraming()
    {
    }

    /**
     * One entry: where it starts (its FULL or FIRST fragment), one past its last fragment, and
     * the fragments that carry it.
     *
     * @param fragments each fragment's header offset and data length
     */
    record EntryRef(int offset, int end, int sequence, int fbLen, int rawLen, List<int[]> fragments, byte flags)
    {
        EntryRef(final int offset, final int end, final int sequence, final int fbLen, final int rawLen, final List<int[]> fragments)
        {
            this(offset, end, sequence, fbLen, rawLen, fragments, (byte) 0);
        }

        /** The first byte of the entry's content, inside its first fragment. */
        int payloadOffset()
        {
            return offset + HEADER;
        }

        /** Bytes from the entry's start to one past its last fragment. */
        int totalLength()
        {
            return end - offset;
        }

        boolean isSplit()
        {
            return fragments.size() > 1;
        }
    }

    static int blockSize(final ByteBuffer file)
    {
        return file.getInt(R7fConstants.PREAMBLE_OFF_BLOCK_SIZE);
    }

    static List<EntryRef> entriesOf(final Path segment) throws IOException
    {
        return entriesOf(ByteBuffer.wrap(Files.readAllBytes(segment)).order(ByteOrder.BIG_ENDIAN));
    }

    /** Every committed PAD fragment's offset, in a compressed segment. */
    static List<Integer> padsOf(final Path segment) throws IOException
    {
        final ByteBuffer file = ByteBuffer.wrap(Files.readAllBytes(segment)).order(ByteOrder.BIG_ENDIAN);
        final List<Integer> pads = new ArrayList<>();
        walk(file, pads);
        return pads;
    }

    static List<EntryRef> entriesOf(final ByteBuffer file)
    {
        return walk(file, new ArrayList<>());
    }

    private static List<EntryRef> walk(final ByteBuffer file, final List<Integer> pads)
    {
        final int blockSize = blockSize(file);
        final boolean compressed = file.getShort(R7fConstants.PREAMBLE_OFF_CODEC) == R7fConstants.CODEC_ZSTD;
        final List<EntryRef> entries = new ArrayList<>();
        int pos = R7fConstants.PREAMBLE_SIZE;
        final ZstdDecompressCtx stream = compressed ? new ZstdDecompressCtx() : null;

        while (true)
        {
            pos = skipPadding(pos, blockSize);
            if (pos + HEADER + 1 > file.limit() || file.getInt(pos) != R7fConstants.FRAGMENT_MAGIC)
            {
                break;
            }
            final byte type = file.get(pos + 4);
            final byte flags = file.get(pos + 5);
            if (compressed && flags == R7fConstants.FLAG_PAD)
            {
                pads.add(pos);
                pos += HEADER + file.getInt(pos + 8);
                continue;
            }
            final List<int[]> fragments = new ArrayList<>();
            fragments.add(new int[]{pos, file.getInt(pos + 8)});
            int end = pos + HEADER + file.getInt(pos + 8);

            if (type == R7fConstants.FRAGMENT_FIRST)
            {
                int at = end;
                boolean complete = false;
                while (at + HEADER + 1 <= file.limit() && file.getInt(at) == R7fConstants.FRAGMENT_MAGIC)
                {
                    final int length = file.getInt(at + 8);
                    fragments.add(new int[]{at, length});
                    end = at + HEADER + length;
                    if (file.get(at + 4) == R7fConstants.FRAGMENT_LAST)
                    {
                        complete = true;
                        break;
                    }
                    at += blockSize;
                }
                if (!complete)
                {
                    break;
                }
            }
            else if (type != R7fConstants.FRAGMENT_FULL)
            {
                break;
            }

            final byte[] head = compressed
                    ? Arrays.copyOf(plainContent(file, fragments, flags, stream), R7fConstants.ENTRY_CONTENT_HEADER_SIZE)
                    : content(file, fragments, 0, R7fConstants.ENTRY_CONTENT_HEADER_SIZE);
            final ByteBuffer h = ByteBuffer.wrap(head);
            entries.add(new EntryRef(pos, end, h.getInt(0), h.getInt(4), h.getInt(8), List.copyOf(fragments), flags));
            pos = end;
        }
        return entries;
    }

    /**
     * The plain content of a compressed entry: its stream piece decoded after the earlier ones
     * in the block, or its standalone frame.
     */
    private static byte[] plainContent(final ByteBuffer file, final List<int[]> fragments, final byte flags,
                                       final ZstdDecompressCtx stream)
    {
        int length = 0;
        for (final int[] fragment : fragments)
        {
            length += fragment[1];
        }
        final byte[] data = content(file, fragments, 0, length);
        if (flags == R7fConstants.FLAG_STANDALONE)
        {
            final int plain = ByteBuffer.wrap(data).getInt(0);
            return Zstd.decompress(Arrays.copyOfRange(data, Integer.BYTES, data.length), plain);
        }
        if (flags == R7fConstants.FLAG_STREAM_START)
        {
            stream.reset();
        }
        final ByteBuffer source = ByteBuffer.allocateDirect(data.length).put(data).flip();
        final ByteBuffer target = ByteBuffer.allocateDirect(4 * blockSize(file));
        // A flushed piece decodes completely; call until nothing more is consumed or produced
        while (true)
        {
            final int consumed = source.position();
            final int produced = target.position();
            stream.decompressDirectByteBufferStream(target, source);
            if (source.position() == consumed && target.position() == produced)
            {
                break;
            }
        }
        final byte[] out = new byte[target.position()];
        target.flip().get(out);
        return out;
    }

    /** The entry's content, gathered from its fragments. */
    static byte[] contentOf(final ByteBuffer file, final EntryRef entry)
    {
        int length = 0;
        for (final int[] fragment : entry.fragments())
        {
            length += fragment[1];
        }
        return content(file, entry.fragments(), 0, length);
    }

    static int skipPadding(final int pos, final int blockSize)
    {
        final int remaining = blockSize - (pos % blockSize);
        return remaining < HEADER + 1 ? pos + remaining : pos;
    }

    /**
     * Rewrites an entry's sequence number and repairs the CRCs it touches, so the entry stays
     * structurally valid and only the sequence is wrong.
     */
    static void rewriteSequence(final Path file, final EntryRef entry, final int newSequence) throws IOException
    {
        final byte[] bytes = Files.readAllBytes(file);
        final byte[] value = ByteBuffer.allocate(4).putInt(newSequence).array();
        writeContent(bytes, entry.fragments(), 0, value);
        for (final int[] fragment : entry.fragments())
        {
            repairCrc(bytes, fragment[0]);
        }
        Files.write(file, bytes);
    }

    /**
     * A complete, CRC-valid FULL fragment carrying {@code content}: what a writer would have
     * produced, for building forgeries.
     */
    static byte[] fullFragment(final byte[] content)
    {
        final byte[] fragment = new byte[HEADER + content.length];
        final ByteBuffer b = ByteBuffer.wrap(fragment);
        b.putInt(0, R7fConstants.FRAGMENT_MAGIC);
        b.put(4, R7fConstants.FRAGMENT_FULL);
        b.putInt(8, content.length);
        System.arraycopy(content, 0, fragment, HEADER, content.length);
        repairCrc(fragment, 0);
        return fragment;
    }

    /**
     * Recomputes the CRC of the fragment whose header is at {@code at}: type, flags, reserved,
     * length, then the data.
     */
    static void repairCrc(final byte[] bytes, final int at)
    {
        final ByteBuffer b = ByteBuffer.wrap(bytes);
        final int length = b.getInt(at + 8);
        final CRC32C crc = new CRC32C();
        crc.update(bytes, at + 4, 8);
        crc.update(bytes, at + HEADER, length);
        b.putInt(at + 12, (int) crc.getValue());
    }

    private static byte[] content(final ByteBuffer file, final List<int[]> fragments, final int from, final int length)
    {
        final byte[] out = new byte[length];
        int logical = 0;
        int copied = 0;
        for (final int[] fragment : fragments)
        {
            for (int i = 0; i < fragment[1] && copied < length; i++, logical++)
            {
                if (logical >= from)
                {
                    out[copied++] = file.get(fragment[0] + HEADER + i);
                }
            }
        }
        return out;
    }

    private static void writeContent(final byte[] bytes, final List<int[]> fragments, final int from, final byte[] value)
    {
        int logical = 0;
        int written = 0;
        for (final int[] fragment : fragments)
        {
            for (int i = 0; i < fragment[1] && written < value.length; i++, logical++)
            {
                if (logical >= from)
                {
                    bytes[fragment[0] + HEADER + i] = value[written++];
                }
            }
        }
    }
}
