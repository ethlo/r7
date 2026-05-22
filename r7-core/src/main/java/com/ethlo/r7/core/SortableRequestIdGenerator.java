package com.ethlo.r7.core;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

public class SortableRequestIdGenerator implements RequestIdGenerator
{
    // Base58 alphabet: Excludes 0, O, I, and l
    private static final char[] ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final int TIMESTAMP_CHARS = 8;
    private static final int RANDOM_CHARS = 8;
    private static final int TOTAL_LENGTH = TIMESTAMP_CHARS + RANDOM_CHARS;

    // Fast O(1) lookup for parsing
    private static final int[] DECODE_MAP = new int[128];

    static
    {
        Arrays.fill(DECODE_MAP, -1);
        for (int i = 0; i < ALPHABET.length; i++)
        {
            DECODE_MAP[ALPHABET[i]] = i;
        }
    }

    @Override
    public String generate()
    {
        final char[] buffer = new char[TOTAL_LENGTH];
        final ThreadLocalRandom rng = ThreadLocalRandom.current();

        long timestamp = System.currentTimeMillis();
        long random = rng.nextLong() & Long.MAX_VALUE;

        // Fill timestamp (index 0 to 7)
        // Fixed loop ensures exactly 8 characters, padding with ALPHABET[0] ('1') if needed
        for (int i = TIMESTAMP_CHARS - 1; i >= 0; i--)
        {
            buffer[i] = ALPHABET[(int) (timestamp % 58)];
            timestamp /= 58;
        }

        // Fill random (index 8 to 15)
        for (int i = TOTAL_LENGTH - 1; i >= TIMESTAMP_CHARS; i--)
        {
            buffer[i] = ALPHABET[(int) (random % 58)];
            random /= 58;
        }

        return new String(buffer);
    }

    /**
     * Extracts the millisecond epoch from the first 8 characters of the ID.
     *
     * @param id The 16-character Base58 ID
     * @return The epoch millisecond timestamp
     */
    public long parseTimestamp(final String id)
    {
        if (id == null || id.length() < TIMESTAMP_CHARS)
        {
            throw new IllegalArgumentException("ID must be at least " + TIMESTAMP_CHARS + " characters long");
        }

        long timestamp = 0L;
        for (int i = 0; i < TIMESTAMP_CHARS; i++)
        {
            final char c = id.charAt(i);
            final int value = (c < 128) ? DECODE_MAP[c] : -1;
            if (value == -1)
            {
                throw new IllegalArgumentException("Invalid Base58 character in timestamp: " + c);
            }
            timestamp = (timestamp * 58) + value;
        }
        return timestamp;
    }
}