package com.ethlo.r7.util;

/**
 * ASCII-only case folding for header names, without allocating.
 * <p>
 * Header names are ASCII tokens per RFC 9110 and compare case-insensitively. Folding them with
 * {@link String#toLowerCase()} allocates a string per lookup and, without an explicit locale,
 * is not even correct: under a Turkish locale {@code I} folds to {@code ı}, so {@code IF-MATCH}
 * would stop matching {@code if-match}. {@link String#equalsIgnoreCase} is locale-independent
 * but folds beyond ASCII (the Kelvin sign {@code U+212A} equals {@code k}), which would let a
 * lookup match a name no header on the wire could carry. Only {@code A-Z} fold here; every
 * other character compares exactly.
 */
public final class AsciiCase
{
    private AsciiCase()
    {
    }

    public static char toLower(final char c)
    {
        return (c >= 'A' && c <= 'Z') ? (char) (c + 'a' - 'A') : c;
    }

    public static boolean equalsIgnoreCase(final String a, final String b)
    {
        if (a == b)
        {
            return true;
        }
        if (a == null || b == null)
        {
            return false;
        }
        final int len = a.length();
        if (len != b.length())
        {
            return false;
        }
        for (int i = 0; i < len; i++)
        {
            final char ca = a.charAt(i);
            final char cb = b.charAt(i);
            if (ca != cb && toLower(ca) != toLower(cb))
            {
                return false;
            }
        }
        return true;
    }
}
