package com.ethlo.r7.journal;

import java.util.Collection;
import java.util.Set;

/**
 * A fixed set of header names with a case-insensitive membership test that allocates nothing.
 * <p>
 * The redaction policy is consulted once per header per journaled message, which is four
 * times per exchange for the request set alone. Doing that as
 * {@code name.toLowerCase()} into a {@link Set#contains} allocates a string for every header
 * whose name is not already lower case — which, for traffic from a browser, is all of them.
 * <p>
 * Case folding here is ASCII-only, and deliberately. Header names are ASCII tokens per
 * RFC 9110, {@link String#toLowerCase()} without an explicit locale is not (under a Turkish
 * locale it folds {@code I} to {@code ı}, so {@code IF-MATCH} would stop matching
 * {@code if-match}), and a name carrying anything outside ASCII cannot match a member of
 * this set anyway — so reporting it as absent is both correct and the conservative answer.
 */
public final class HeaderNameSet
{
    private final Set<String> names;
    private final String[] table;
    private final int mask;

    private HeaderNameSet(final Set<String> names)
    {
        this.names = names;

        // Sparse on purpose: the table is tens of entries, it is read on the request path,
        // and the memory saved by a tighter fit would not pay for the extra probes.
        int capacity = 16;
        while (capacity < names.size() * 4)
        {
            capacity <<= 1;
        }
        this.table = new String[capacity];
        this.mask = capacity - 1;

        for (final String name : names)
        {
            insert(name);
        }
    }

    public static HeaderNameSet of(final Collection<String> names)
    {
        return new HeaderNameSet(Set.copyOf(names));
    }

    /**
     * The names themselves, for callers that need to enumerate the policy rather than test
     * against it.
     */
    public Set<String> names()
    {
        return names;
    }

    public boolean contains(final String name)
    {
        if (name == null)
        {
            return false;
        }
        int index = hash(name) & mask;
        while (true)
        {
            final String candidate = table[index];
            if (candidate == null)
            {
                return false;
            }
            if (equalsAsciiIgnoreCase(candidate, name))
            {
                return true;
            }
            index = (index + 1) & mask;
        }
    }

    private void insert(final String name)
    {
        int index = hash(name) & mask;
        while (table[index] != null)
        {
            index = (index + 1) & mask;
        }
        table[index] = name;
    }

    private static int hash(final String s)
    {
        int h = 0;
        for (int i = 0, len = s.length(); i < len; i++)
        {
            h = 31 * h + toLowerAscii(s.charAt(i));
        }
        return h ^ (h >>> 16);
    }

    /**
     * Folds with exactly the rule {@link #hash} folds with. A comparison that considered two
     * names equal where the hash had placed them in different buckets would make membership
     * depend on insertion order.
     */
    private static boolean equalsAsciiIgnoreCase(final String a, final String b)
    {
        final int len = a.length();
        if (len != b.length())
        {
            return false;
        }
        for (int i = 0; i < len; i++)
        {
            if (toLowerAscii(a.charAt(i)) != toLowerAscii(b.charAt(i)))
            {
                return false;
            }
        }
        return true;
    }

    private static char toLowerAscii(final char c)
    {
        return (c >= 'A' && c <= 'Z') ? (char) (c + 'a' - 'A') : c;
    }
}
