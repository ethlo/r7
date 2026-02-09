package com.ethlo.venturi.core;

import java.util.concurrent.ThreadLocalRandom;

public class SortableRequestIdGenerator implements RequestIdGenerator
{
    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int TIMESTAMP_CHARS = 9; // Enough for ~2000 years in Base36
    private static final int RANDOM_CHARS = 11;    // Equivalent to ~56 bits

    @Override
    public CharSequence generate()
    {
        final char[] buffer = new char[TIMESTAMP_CHARS + 1 + RANDOM_CHARS];
        long timestamp = System.currentTimeMillis();
        long random = ThreadLocalRandom.current().nextLong() & 0x7FFFFFFFFFFFFFFFL;

        // Fill timestamp part (right to left)
        for (int i = TIMESTAMP_CHARS - 1; i >= 0; i--)
        {
            buffer[i] = ALPHABET[(int) (timestamp % 36)];
            timestamp /= 36;
        }

        buffer[TIMESTAMP_CHARS] = '-';

        // Fill random part (right to left)
        for (int i = buffer.length - 1; i > TIMESTAMP_CHARS; i--)
        {
            buffer[i] = ALPHABET[(int) (random % 36)];
            random /= 36;
        }

        return new FastCharSequence(buffer);
    }

    private record FastCharSequence(char[] data) implements CharSequence
    {
        @Override
        public int length()
        {
            return data.length;
        }

        @Override
        public char charAt(int index)
        {
            return data[index];
        }

        @Override
        public CharSequence subSequence(int start, int end)
        {
            return new String(data, start, end - start);
        }

        @Override
        public String toString()
        {
            return new String(data);
        }
    }
}