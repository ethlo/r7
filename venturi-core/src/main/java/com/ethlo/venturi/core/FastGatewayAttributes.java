package com.ethlo.venturi.core;

import com.ethlo.venturi.api.GatewayAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * High-performance, zero-allocation (mostly) implementation of GatewayAttributes.
 * Uses a flat array for linear scanning to maximize L1/L2 cache locality.
 */
public final class FastGatewayAttributes implements GatewayAttributes
{
    private static final int INITIAL_CAPACITY = 8; // 4 key-value pairs
    private Object[] data = new Object[INITIAL_CAPACITY];
    private int size = 0;

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(CharSequence key)
    {
        for (int i = 0; i < size; i += 2)
        {
            if (contentEquals(key, (CharSequence) data[i]))
            {
                return (T) data[i + 1];
            }
        }
        return null;
    }

    @Override
    public void put(CharSequence key, Object value)
    {
        // Check for existing key to update
        for (int i = 0; i < size; i += 2)
        {
            if (contentEquals(key, (CharSequence) data[i]))
            {
                data[i + 1] = value;
                return;
            }
        }

        // Expand if full
        if (size + 2 > data.length)
        {
            final Object[] newData = new Object[data.length * 2];
            System.arraycopy(data, 0, newData, 0, data.length);
            data = newData;
        }

        // Append new pair
        data[size++] = key;
        data[size++] = value;
    }

    @Override
    public Iterable<CharSequence> attributeNames()
    {
        final List<CharSequence> names = new ArrayList<>(size / 2);
        for (int i = 0; i < size; i += 2)
        {
            names.add((CharSequence) data[i]);
        }
        return names;
    }

    /**
     * Efficiently compare CharSequences without toString() allocation.
     */
    private boolean contentEquals(CharSequence a, CharSequence b)
    {
        if (a == b)
        {
            return true;
        }
        if (a == null || b == null)
        {
            return false;
        }
        int len = a.length();
        if (len != b.length())
        {
            return false;
        }
        for (int i = 0; i < len; i++)
        {
            if (a.charAt(i) != b.charAt(i))
            {
                return false;
            }
        }
        return true;
    }
}