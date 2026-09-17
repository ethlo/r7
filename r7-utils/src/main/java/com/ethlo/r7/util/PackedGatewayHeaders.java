package com.ethlo.r7.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.ethlo.r7.api.EntryConsumer;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.StatefulEntryConsumer;

/**
 * Headers held as the bytes they were, decoded only when something asks for them.
 * <p>
 * The alternative — one {@link String} per name and per value — costs two objects and their
 * headers and padding to hold a few dozen characters, which is several times what the text
 * itself occupies. That is affordable for headers being read and dropped, and expensive for
 * headers being <em>retained</em>: a reader reassembling exchanges holds four header sets per
 * exchange in flight, and those sets dominate its heap. Body fragments in the same reader are
 * already kept as raw bytes for exactly this reason; headers were the exception.
 * <p>
 * The encoding is a single array of {@code [varint nameLen][name][varint valueLen][value]}
 * repeated, in arrival order. Order and multiplicity are therefore preserved exactly, which
 * matters because both are part of the record being audited.
 * <p>
 * Text is ISO-8859-1, the encoding HTTP/1.1 header fields use on the wire and the one the
 * journal stores, so values round-trip byte for byte.
 * <p>
 * Immutable and safe to share once built. Lookups compare without decoding, so only a value
 * actually returned is ever materialised.
 */
public final class PackedGatewayHeaders implements GatewayHeaders
{
    private static final byte[] NO_BYTES = new byte[0];

    private final byte[] data;
    private final int count;

    private PackedGatewayHeaders(final byte[] data, final int count)
    {
        this.data = data;
        this.count = count;
    }

    public static GatewayHeaders empty()
    {
        return new PackedGatewayHeaders(NO_BYTES, 0);
    }

    /**
     * Packs a header view into a self-contained copy. Entries with a null name or value are
     * skipped, as they cannot be represented and carry no information.
     *
     * @return null when {@code source} is null, mirroring the copy it replaces
     */
    public static GatewayHeaders pack(final GatewayHeaders source)
    {
        if (source == null)
        {
            return null;
        }

        final Packer packer = new Packer();
        source.forEach(packer, Packer::append);
        return new PackedGatewayHeaders(packer.trimmed(), packer.count);
    }

    @Override
    public int forEach(final EntryConsumer consumer)
    {
        int position = 0;
        for (int i = 0; i < count; i++)
        {
            final int nameLength = varIntAt(position);
            position = afterVarIntAt(position);
            final int nameStart = position;
            position += nameLength;

            final int valueLength = varIntAt(position);
            position = afterVarIntAt(position);
            final int valueStart = position;
            position += valueLength;

            consumer.accept(decode(nameStart, nameLength), decode(valueStart, valueLength));
        }
        return count;
    }

    @Override
    public <S> int forEach(final S state, final StatefulEntryConsumer<S> consumer)
    {
        int position = 0;
        for (int i = 0; i < count; i++)
        {
            final int nameLength = varIntAt(position);
            position = afterVarIntAt(position);
            final int nameStart = position;
            position += nameLength;

            final int valueLength = varIntAt(position);
            position = afterVarIntAt(position);
            final int valueStart = position;
            position += valueLength;

            consumer.accept(state, decode(nameStart, nameLength), decode(valueStart, valueLength));
        }
        return count;
    }

    /**
     * Exact-match lookup, as the String-backed container this replaces did — HTTP header names
     * are case-insensitive, but changing that here would change behaviour rather than
     * representation.
     */
    @Override
    public String getFirst(final String name)
    {
        if (name == null)
        {
            return null;
        }
        int position = 0;
        for (int i = 0; i < count; i++)
        {
            final int nameLength = varIntAt(position);
            position = afterVarIntAt(position);
            final int nameStart = position;
            position += nameLength;

            final int valueLength = varIntAt(position);
            position = afterVarIntAt(position);
            final int valueStart = position;
            position += valueLength;

            if (matches(name, nameStart, nameLength))
            {
                return decode(valueStart, valueLength);
            }
        }
        return null;
    }

    @Override
    public Iterable<String> getAll(final String name)
    {
        if (name == null)
        {
            return List.of();
        }
        final List<String> values = new ArrayList<>(1);
        int position = 0;
        for (int i = 0; i < count; i++)
        {
            final int nameLength = varIntAt(position);
            position = afterVarIntAt(position);
            final int nameStart = position;
            position += nameLength;

            final int valueLength = varIntAt(position);
            position = afterVarIntAt(position);
            final int valueStart = position;
            position += valueLength;

            if (matches(name, nameStart, nameLength))
            {
                values.add(decode(valueStart, valueLength));
            }
        }
        return values;
    }

    /**
     * Compares against the stored bytes without building a string for the candidate. Each
     * stored byte is one ISO-8859-1 character, so an unsigned byte compares directly to a char.
     */
    private boolean matches(final String candidate, final int start, final int length)
    {
        if (candidate.length() != length)
        {
            return false;
        }
        for (int i = 0; i < length; i++)
        {
            if (candidate.charAt(i) != (char) (data[start + i] & 0xFF))
            {
                return false;
            }
        }
        return true;
    }

    private String decode(final int start, final int length)
    {
        return new String(data, start, length, StandardCharsets.ISO_8859_1);
    }

    /**
     * Byte equality, which is exactly header-sequence equality: the encoding is canonical, so
     * two sets pack to the same bytes if and only if they carry the same names and values, in
     * the same order, the same number of times.
     * <p>
     * This is what lets a holder keep one copy where it would otherwise keep two identical
     * ones. The length check in front makes the common answer — not equal — cost almost
     * nothing.
     */
    @Override
    public boolean equals(final Object other)
    {
        if (this == other)
        {
            return true;
        }
        if (!(other instanceof PackedGatewayHeaders that))
        {
            return false;
        }
        return count == that.count && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode()
    {
        return Arrays.hashCode(data);
    }

    private int varIntAt(final int position)
    {
        int value = 0;
        int shift = 0;
        int index = position;
        while (true)
        {
            final byte b = data[index++];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0)
            {
                return value;
            }
            shift += 7;
        }
    }

    private int afterVarIntAt(final int position)
    {
        int index = position;
        while ((data[index] & 0x80) != 0)
        {
            index++;
        }
        return index + 1;
    }

    /**
     * Builds the packed array. Grows by doubling while appending and is trimmed to its exact
     * length at the end, because a half-empty buffer retained for the life of an exchange would
     * give back the memory this class exists to save.
     */
    private static final class Packer
    {
        private byte[] buffer = new byte[256];
        private int length;
        private int count;

        private void append(final String name, final String value)
        {
            if (name == null || value == null)
            {
                return;
            }
            ensure(varIntSize(name.length()) + name.length() + varIntSize(value.length()) + value.length());
            writeLengthPrefixed(name);
            writeLengthPrefixed(value);
            count++;
        }

        private void writeLengthPrefixed(final String text)
        {
            final int textLength = text.length();
            int value = textLength;
            while ((value & ~0x7F) != 0)
            {
                buffer[length++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            buffer[length++] = (byte) value;

            for (int i = 0; i < textLength; i++)
            {
                // Truncating to the low byte is the ISO-8859-1 encoding, and matches how the
                // journal writer stores these same characters.
                buffer[length++] = (byte) text.charAt(i);
            }
        }

        private static int varIntSize(final int value)
        {
            int size = 1;
            int remaining = value >>> 7;
            while (remaining != 0)
            {
                size++;
                remaining >>>= 7;
            }
            return size;
        }

        private void ensure(final int additional)
        {
            if (length + additional <= buffer.length)
            {
                return;
            }
            int capacity = buffer.length;
            while (capacity < length + additional)
            {
                capacity <<= 1;
            }
            buffer = Arrays.copyOf(buffer, capacity);
        }

        private byte[] trimmed()
        {
            return length == buffer.length ? buffer : Arrays.copyOf(buffer, length);
        }
    }
}
