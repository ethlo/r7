package com.ethlo.venturi.util;

/**
 * Utility for performing high-performance, allocation-free comparisons on {@link CharSequence} instances.
 */
public class CharSequenceUtil
{
    /**
     * Compares a specific region of two {@link CharSequence} instances for equality.
     *
     * @param a          The first sequence
     * @param aStart     Offset in the first sequence
     * @param aLength    The length of the region in the first sequence
     * @param b          The second sequence
     * @param bStart     Offset in the second sequence
     * @param bLength    The length of the region in the second sequence
     * @param ignoreCase Whether to ignore case
     * @return {@code true} if the lengths match AND the content matches
     */
    public static boolean regionEquals(CharSequence a, int aStart, int aLength, CharSequence b, int bStart, int bLength, boolean ignoreCase)
    {
        if (a == b)
        {
            return true;
        }

        if (aLength != bLength)
        {
            return false;
        }

        for (int i = 0; i < aLength; i++)
        {
            final char c1 = a.charAt(aStart + i);
            final char c2 = b.charAt(bStart + i);

            if (ignoreCase)
            {
                if (c1 != c2 && Character.toLowerCase(c1) != Character.toLowerCase(c2))
                {
                    return false;
                }
            }
            else if (c1 != c2)
            {
                return false;
            }
        }
        return true;
    }


    /**
     * Checks if two {@link CharSequence} instances are equal.
     *
     * @param a The first sequence
     * @param b The second sequence
     * @return {@code true} if the sequences match exactly
     */
    public static boolean equals(CharSequence a, CharSequence b)
    {
        return regionEquals(a, 0, a.length(), b, 0, b.length(), false);
    }

    /**
     * Checks if two {@link CharSequence} instances are equal, ignoring case.
     *
     * @param a The first sequence
     * @param b The second sequence
     * @return {@code true} if the sequences match regardless of case
     */
    public static boolean equalsIgnoreCase(CharSequence a, CharSequence b)
    {
        return regionEquals(a, 0, a.length(), b, 0, b.length(), true);
    }

    public static int hash(CharSequence cs)
    {
        int h = 0;
        int len = cs.length();
        for (int i = 0; i < len; i++)
        {
            h = 31 * h + cs.charAt(i);
        }
        // Bit-mix to prevent clustering in the arrays
        h ^= (h >>> 20) ^ (h >>> 12);
        return h ^ (h >>> 7) ^ (h >>> 4);
    }
}
