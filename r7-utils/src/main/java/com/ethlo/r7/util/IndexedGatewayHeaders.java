package com.ethlo.r7.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.ethlo.r7.api.EntryConsumer;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.StatefulEntryConsumer;

/**
 * A header set held as two parallel arrays, readable by position.
 * <p>
 * For a consumer that reads one set more than once, or needs it by index: the journal encoder
 * writes a set in full and then diffs the next set against it by position. Going through
 * {@link #forEach} each time repeats whatever work produced the values - for the journal that is
 * redaction, a safe-list lookup per header and a fingerprint per unsafe value - and a delta
 * against a set that only offers traversal has to copy it into arrays first anyway. Filled once,
 * in order, and read-only after that by convention.
 * <p>
 * {@link #append} does not validate. What goes in has either come off the wire or been
 * validated where a filter set it ({@code MutableGatewayHeaders}), and {@code TextValues}
 * documents keeping that check off the journal's path.
 */
public final class IndexedGatewayHeaders implements GatewayHeaders
{
    private static final String[] NONE = new String[0];

    private String[] names;
    private String[] values;
    private int size;

    public IndexedGatewayHeaders(final int expectedSize)
    {
        this.names = expectedSize == 0 ? NONE : new String[expectedSize];
        this.values = expectedSize == 0 ? NONE : new String[expectedSize];
    }

    public void append(final String name, final String value)
    {
        if (size == names.length)
        {
            final int capacity = Math.max(8, names.length * 2);
            names = Arrays.copyOf(names, capacity);
            values = Arrays.copyOf(values, capacity);
        }
        names[size] = name;
        values[size] = value;
        size++;
    }

    public int size()
    {
        return size;
    }

    public String name(final int index)
    {
        return names[index];
    }

    public String value(final int index)
    {
        return values[index];
    }

    @Override
    public String getFirst(final String name)
    {
        for (int i = 0; i < size; i++)
        {
            if (names[i].equalsIgnoreCase(name))
            {
                return values[i];
            }
        }
        return null;
    }

    @Override
    public Iterable<String> getAll(final String name)
    {
        final List<String> all = new ArrayList<>();
        for (int i = 0; i < size; i++)
        {
            if (names[i].equalsIgnoreCase(name))
            {
                all.add(values[i]);
            }
        }
        return all;
    }

    @Override
    public int forEach(final EntryConsumer consumer)
    {
        for (int i = 0; i < size; i++)
        {
            consumer.accept(names[i], values[i]);
        }
        return size;
    }

    @Override
    public <S> int forEach(final S state, final StatefulEntryConsumer<S> consumer)
    {
        for (int i = 0; i < size; i++)
        {
            consumer.accept(state, names[i], values[i]);
        }
        return size;
    }
}
