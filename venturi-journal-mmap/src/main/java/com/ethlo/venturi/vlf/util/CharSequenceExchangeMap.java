package com.ethlo.venturi.vlf.util;

import static com.ethlo.venturi.util.CharSequenceUtil.hash;

import java.util.function.Function;

import com.ethlo.venturi.journal.api.JournalExchange;
import com.ethlo.venturi.util.CharSequenceUtil;

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

    public CharSequenceExchangeMap(int initialCapacity)
    {
        // Ensure capacity is a power of 2 for fast bitwise masking
        int c = Integer.highestOneBit(initialCapacity - 1) << 1;
        this.keys = new CharSequence[c];
        this.values = new JournalExchange[c];
        this.mask = c - 1;
    }

    public void put(CharSequence key, JournalExchange value)
    {
        if (size * 2 > keys.length) resize();

        int idx = hash(key) & mask;
        while (keys[idx] != null)
        {
            if (CharSequenceUtil.equals(keys[idx], key))
            {
                values[idx] = value; // Update existing
                return;
            }
            idx = (idx + 1) & mask; // Linear probe
        }

        keys[idx] = key;
        values[idx] = value;
        size++;
    }

    public JournalExchange get(CharSequence key)
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

    public JournalExchange remove(CharSequence key)
    {
        int idx = hash(key) & mask;
        while (keys[idx] != null)
        {
            if (CharSequenceUtil.equals(keys[idx], key))
            {
                JournalExchange removed = values[idx];
                keys[idx] = null;
                values[idx] = null;
                size--;
                shift(idx); // Close the gap to maintain probe sequences
                return removed;
            }
            idx = (idx + 1) & mask;
        }
        return null;
    }

    /**
     * Shifts subsequent elements backward to fill the gap left by a removal.
     * This avoids the need for allocating 'Tombstone' objects.
     */
    private void shift(int pos)
    {
        int last = pos;
        int idx = (pos + 1) & mask;

        while (keys[idx] != null)
        {
            int slot = hash(keys[idx]) & mask;

            // If the element at idx belongs at or before 'last' (accounting for wrap-around)
            if (last <= idx ? (last >= slot || slot > idx) : (last >= slot && slot > idx))
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
        CharSequence[] oldKeys = keys;
        JournalExchange[] oldValues = values;

        int newCap = oldKeys.length * 2;
        keys = new CharSequence[newCap];
        values = new JournalExchange[newCap];
        mask = newCap - 1;
        size = 0;

        for (int i = 0; i < oldKeys.length; i++)
        {
            if (oldKeys[i] != null)
            {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    /**
     * If the specified key is not already associated with a value, attempts to compute its value
     * using the given mapping function and enters it into this map.
     */
    public JournalExchange computeIfAbsent(CharSequence key, Function<? super CharSequence, ? extends JournalExchange> mappingFunction)
    {
        // 1. Check capacity BEFORE probing. Resizing invalidates any indices we find.
        if (size * 2 > keys.length)
        {
            resize();
        }

        int idx = hash(key) & mask;

        // 2. Probe for an existing key
        while (keys[idx] != null)
        {
            if (CharSequenceUtil.equals(keys[idx], key))
            {
                // Cache hit: Return the existing exchange
                return values[idx];
            }
            idx = (idx + 1) & mask; // Linear probe
        }

        // 3. Cache miss: We are now sitting on the correct empty slot (keys[idx] == null).
        // Execute the mapping function to create the new exchange.
        JournalExchange newValue = mappingFunction.apply(key);

        // 4. Insert directly into the arrays
        keys[idx] = key;
        values[idx] = newValue;
        size++;

        return newValue;
    }
}