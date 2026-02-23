package com.ethlo.venturi.util;

import java.util.Arrays;
import java.util.Iterator;
import java.util.function.BiConsumer;

public abstract class ArrayBackedPairStorage<K, V>
{
    private final ReusableKeysView keysView = new ReusableKeysView();
    private final ReusableValuesView valuesView = new ReusableValuesView();
    protected Object[] data;
    protected int size = 0;

    protected ArrayBackedPairStorage(int initialCapacity)
    {
        this.data = new Object[initialCapacity * 2];
    }

    protected abstract boolean keysEqual(K requestedKey, K storedKey);

    // --- The "Internal" API for Subclasses ---

    protected void addInternal(K key, V value)
    {
        putInternal(key, value, true);
    }

    protected void setInternal(K key, V value)
    {
        putInternal(key, value, false);
    }

    protected V getFirstInternal(K key)
    {
        int idx = findKey(key);
        return idx != -1 ? (V) data[idx + 1] : null;
    }

    protected Iterable<V> getAllInternal(K key)
    {
        return valuesView.prepare(key);
    }

    protected void removeInternal(K key)
    {
        for (int i = 0; i < size; i += 2)
        {
            if (keysEqual(key, (K) data[i]))
            {
                int moveCount = size - i - 2;
                if (moveCount > 0)
                {
                    System.arraycopy(data, i + 2, data, i, moveCount);
                }
                size -= 2;
                data[size] = null;
                data[size + 1] = null;
                i -= 2;
            }
        }
    }

    protected int forEachInternal(BiConsumer<? super K, ? super V> consumer)
    {
        final int currentSize = this.size;
        final Object[] d = this.data;
        for (int i = 0; i < currentSize; i += 2)
        {
            consumer.accept((K) d[i], (V) d[i + 1]);
        }
        return currentSize >> 1;
    }

    protected Iterable<K> getKeysInternal()
    {
        return keysView;
    }

    // --- Private Plumbing ---

    private int findKey(K key)
    {
        for (int i = 0; i < size; i += 2)
        {
            if (keysEqual(key, (K) data[i])) return i;
        }
        return -1;
    }

    private void putInternal(K key, V value, boolean allowDuplicates)
    {
        if (!allowDuplicates)
        {
            int idx = findKey(key);
            if (idx != -1)
            {
                data[idx + 1] = value;
                return;
            }
        }
        ensureCapacity(size + 2);
        data[size++] = key;
        data[size++] = value;
    }

    private void ensureCapacity(int min)
    {
        if (min > data.length)
        {
            data = Arrays.copyOf(data, Math.max(data.length * 2, min));
        }
    }

    // --- Reusable Views (Zero Allocation) ---

    private class ReusableKeysView implements Iterable<K>, Iterator<K>
    {
        private int cursor = 0;

        @Override
        public Iterator<K> iterator()
        {
            cursor = 0;
            return this;
        }

        @Override
        public boolean hasNext()
        {
            return cursor < size;
        }

        @Override
        @SuppressWarnings("unchecked")
        public K next()
        {
            K key = (K) data[cursor];
            cursor += 2;
            return key;
        }
    }

    private class ReusableValuesView implements Iterable<V>, Iterator<V>
    {
        private K target;
        private int cursor = 0;

        Iterable<V> prepare(K target)
        {
            this.target = target;
            return this;
        }

        @Override
        public Iterator<V> iterator()
        {
            cursor = 0;
            return this;
        }

        @Override
        public boolean hasNext()
        {
            while (cursor < size)
            {
                if (keysEqual(target, (K) data[cursor])) return true;
                cursor += 2;
            }
            return false;
        }

        @Override
        @SuppressWarnings("unchecked")
        public V next()
        {
            V val = (V) data[cursor + 1];
            cursor += 2;
            return val;
        }
    }

    @FunctionalInterface
    public interface StateConsumer<S, K, V>
    {
        void accept(S state, K key, V value);
    }

    protected <S> int forEachInternal(S state, StateConsumer<S, ? super K, ? super V> consumer)
    {
        final int currentSize = this.size;
        final Object[] d = this.data;
        for (int i = 0; i < currentSize; i += 2)
        {
            consumer.accept(state, (K) d[i], (V) d[i + 1]);
        }
        return currentSize >> 1;
    }
}