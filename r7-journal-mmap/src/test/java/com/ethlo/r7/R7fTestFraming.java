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

/**
 * Format-version-2 framing for tests, written from FORMAT.md rather than shared with the
 * production reader: a check that used the code it checks would agree with it about anything.
 * <p>
 * Only walks committed entries from the start of a segment and stops at the first thing that
 * is not one; it does no resynchronisation. Tests use it to find entries to damage, and to
 * repair or forge framing on purpose.
 * <p>
 * Compressed segments (codec 2, FORMAT.md 4.4) are decoded here with zstd directly: each
 * record is a batch, and every entry in it is reported with the record's offset and fragments
 * and its index in the batch, so the entry fields are the plain ones either way.
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
    record EntryRef(int offset, int end, int sequence, int fbLen, int rawLen, List<int[]> fragments, int batchIndex, int batchSize)
    {
        EntryRef(final int offset, final int end, final int sequence, final int fbLen, final int rawLen, final List<int[]> fragments)
        {
            this(offset, end, sequence, fbLen, rawLen, fragments, 0, 1);
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

    static List<EntryRef> entriesOf(final ByteBuffer file)
    {
        final int blockSize = blockSize(file);
        final boolean compressed = file.getShort(R7fConstants.PREAMBLE_OFF_CODEC) == R7fConstants.CODEC_ZSTD_BATCH;
        final List<EntryRef> entries = new ArrayList<>();
        int pos = R7fConstants.PREAMBLE_SIZE;

        while (true)
        {
            pos = skipPadding(pos, blockSize);
            if (pos + HEADER + 1 > file.limit() || file.getInt(pos) != R7fConstants.FRAGMENT_MAGIC)
            {
                break;
            }
            final byte type = file.get(pos + 4);
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

            if (compressed)
            {
                final ByteBuffer plain = ByteBuffer.wrap(plainContent(file, fragments));
                final int count = batchCount(file, fragments);
                int at = 0;
                for (int i = 0; i < count; i++)
                {
                    final int fbLen = plain.getInt(at + 4);
                    final int rawLen = plain.getInt(at + 8);
                    entries.add(new EntryRef(pos, end, plain.getInt(at), fbLen, rawLen, List.copyOf(fragments), i, count));
                    at += R7fConstants.ENTRY_CONTENT_HEADER_SIZE + fbLen + rawLen;
                }
            }
            else
            {
                final ByteBuffer h = ByteBuffer.wrap(content(file, fragments, 0, R7fConstants.ENTRY_CONTENT_HEADER_SIZE));
                entries.add(new EntryRef(pos, end, h.getInt(0), h.getInt(4), h.getInt(8), List.copyOf(fragments)));
            }
            pos = end;
        }
        return entries;
    }

    /** A batch's plain content: its entries back to back. */
    static byte[] plainContent(final ByteBuffer file, final List<int[]> fragments)
    {
        final byte[] data = content(file, fragments, 0, recordLength(fragments));
        final int plain = ByteBuffer.wrap(data).getInt(0);
        return Zstd.decompress(Arrays.copyOfRange(data, R7fConstants.BATCH_HEADER_SIZE, data.length), plain);
    }

    private static int batchCount(final ByteBuffer file, final List<int[]> fragments)
    {
        return ByteBuffer.wrap(content(file, fragments, Integer.BYTES, Integer.BYTES)).getInt(0);
    }

    private static int recordLength(final List<int[]> fragments)
    {
        int length = 0;
        for (final int[] fragment : fragments)
        {
            length += fragment[1];
        }
        return length;
    }

    /**
     * The content of a batch holding {@code entries}, each a complete 4.2 entry: what the writer
     * would have placed, for forging batches.
     */
    static byte[] batchContent(final byte[]... entries)
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
                .putInt(plainLength).putInt(entries.length).put(frame).array();
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
