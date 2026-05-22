package com.ethlo.r7.r7f.util;

import java.util.Objects;
import java.util.function.Function;

import com.ethlo.r7.journal.api.JournalExchange;

/**
 * A zero-allocation, open-addressed map optimized for String keys.
 * Eliminates Map.Entry allocations and maximizes CPU cache locality.
 */
public class StringExchangeMap
{
    private String[] keys;
    private JournalExchange[] values;
    private int mask;
    private int size;

    public StringExchangeMap(final int initialCapacity)
    {
        final int capacity = Math.max(2, Integer.highestOneBit(Math.max(0, initialCapacity - 1)) << 1);
        this.keys = new String[capacity];
        this.values = new JournalExchange[capacity];
        this.mask = capacity - 1;
    }

    public void put(final String key, final JournalExchange value)
    {
        if (size * 2 > keys.length)
        {
            resize();
        }

        int idx = key.hashCode() & mask;
        while (keys[idx] != null)
        {
            if (Objects.equals(keys[idx], key))
            {
                values[idx] = value;
                return;
            }
            idx = (idx + 1) & mask;
        }

        keys[idx] = key;
        values[idx] = value;
        size++;
    }

    public JournalExchange get(final String key)
    {
        int idx = key.hashCode() & mask;
        while (keys[idx] != null)
        {
            if (Objects.equals(keys[idx], key))
            {
                return values[idx];
            }
            idx = (idx + 1) & mask;
        }
        return null;
    }

    public JournalExchange remove(final String key)
    {
        int idx = key.hashCode() & mask;
        while (keys[idx] != null)
        {
            if (Objects.equals(keys[idx], key))
            {
                final JournalExchange removed = values[idx];
                keys[idx] = null;
                values[idx] = null;
                size--;
                shift(idx);
                return removed;
            }
            idx = (idx + 1) & mask;
        }
        return null;
    }

    private void shift(final int pos)
    {
        int last = pos;
        int idx = (pos + 1) & mask;

        while (keys[idx] != null)
        {
            final int slot = keys[idx].hashCode() & mask;
            final boolean isBetween = (idx >= last)
                    ? (last < slot && slot <= idx)
                    : (last < slot || slot <= idx);

            if (!isBetween)
            {
                keys[last] = keys[idx];
                values[last] = values[idx];
                keys[idx] = null;
                values[idx] = null;
                last = idx;
            }
            idx = (idx + 1) & mask;
        }
    }

    private void resize()
    {
        final String[] oldKeys = keys;
        final JournalExchange[] oldValues = values;

        final int newCap = oldKeys.length * 2;
        this.keys = new String[newCap];
        this.values = new JournalExchange[newCap];
        this.mask = newCap - 1;
        this.size = 0;

        for (int i = 0; i < oldKeys.length; i++)
        {
            if (oldKeys[i] != null)
            {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    public JournalExchange computeIfAbsent(final String key, final Function<? super String, ? extends JournalExchange> mappingFunction)
    {
        if (size * 2 > keys.length)
        {
            resize();
        }

        int idx = key.hashCode() & mask;
        while (keys[idx] != null)
        {
            if (Objects.equals(keys[idx], key))
            {
                return values[idx];
            }
            idx = (idx + 1) & mask;
        }

        final JournalExchange newValue = mappingFunction.apply(key);
        keys[idx] = key;
        values[idx] = newValue;
        size++;

        return newValue;
    }
}