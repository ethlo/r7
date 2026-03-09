package com.ethlo.r7.vlf.util;

import static com.ethlo.r7.util.CharSequenceUtil.hash;

import java.util.function.Function;

import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.util.CharSequenceUtil;

/**
 * A zero-allocation, open-addressed map optimized for CharSequence keys.
 * Eliminates Map.Entry allocations and maximizes CPU cache locality.
 */
public class CharSequenceExchangeMap
{
    private CharSequence[] keys;
    private JournalExchange[] values;
    private int mask;
    private int size;

    public CharSequenceExchangeMap(final int initialCapacity)
    {
        final int capacity = Math.max(2, Integer.highestOneBit(Math.max(0, initialCapacity - 1)) << 1);
        this.keys = new CharSequence[capacity];
        this.values = new JournalExchange[capacity];
        this.mask = capacity - 1;
    }

    public void put(final CharSequence key, final JournalExchange value)
    {
        if (size * 2 > keys.length)
        {
            resize();
        }

        int idx = hash(key) & mask;
        while (keys[idx] != null)
        {
            if (CharSequenceUtil.equals(keys[idx], key))
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

    public JournalExchange get(final CharSequence key)
    {
        int idx = hash(key) & mask;
        while (keys[idx] != null)
        {
            if (CharSequenceUtil.equals(keys[idx], key))
            {
                return values[idx];
            }
            idx = (idx + 1) & mask;
        }
        return null;
    }

    public JournalExchange remove(final CharSequence key)
    {
        int idx = hash(key) & mask;
        while (keys[idx] != null)
        {
            if (CharSequenceUtil.equals(keys[idx], key))
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
            final int slot = hash(keys[idx]) & mask;
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
        final CharSequence[] oldKeys = keys;
        final JournalExchange[] oldValues = values;

        final int newCap = oldKeys.length * 2;
        this.keys = new CharSequence[newCap];
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

    public JournalExchange computeIfAbsent(final CharSequence key, final Function<? super CharSequence, ? extends JournalExchange> mappingFunction)
    {
        if (size * 2 > keys.length)
        {
            resize();
        }

        int idx = hash(key) & mask;
        while (keys[idx] != null)
        {
            if (CharSequenceUtil.equals(keys[idx], key))
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