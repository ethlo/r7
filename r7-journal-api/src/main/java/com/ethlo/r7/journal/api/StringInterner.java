package com.ethlo.r7.journal.api;

/**
 * Deduplicates the strings of a single exchange as it is reassembled.
 * <p>
 * An exchange arrives as four header sets, and the decoder builds every name and value of each
 * set as a fresh string out of the journal's bytes. Nothing is shared between them even where
 * the content is identical — and it usually is: the request headers are journaled once as the
 * client sent them and once as they went upstream, differing only by whatever the filter chain
 * did, and header names repeat across all four sets regardless.
 * <p>
 * Interning collapses those duplicates to one instance each. It is preferred here over storing
 * the upstream sets as a structural delta against the client sets, which would save a little
 * more: a delta has to encode <em>where</em> an added header sits to rebuild the set, because
 * header order is part of the record being audited, and a reconstruction that quietly reordered
 * an exchange's headers would be a corrupted audit trail that still looked healthy. Interning
 * changes no order, no multiplicity and nothing observable — only how many objects back it.
 * <p>
 * Scoped to one exchange and owned by it, so it dies exactly when the exchange does. A table
 * shared across exchanges would hold request data alive past the exchange and would need an
 * eviction policy of its own, pinned against the in-flight set — the kind of second lifetime
 * rule that this package has learned to avoid.
 */
public final class StringInterner
{
    /**
     * Caps the table so that a pathological exchange cannot turn a per-exchange helper into a
     * per-exchange liability. Past it, strings are returned as they arrived.
     */
    private static final int MAX_ENTRIES = 128;

    private static final int INITIAL_CAPACITY = 32;

    private String[] table = new String[INITIAL_CAPACITY];
    private int size;

    /**
     * Returns an instance equal to {@code value} — the one already held if there is one, and
     * otherwise {@code value} itself, remembered for next time.
     */
    public String intern(final String value)
    {
        if (value == null)
        {
            return null;
        }

        int index = indexFor(table, value);
        final String existing = table[index];
        if (existing != null)
        {
            return existing;
        }

        if (size >= MAX_ENTRIES)
        {
            return value;
        }

        // Kept at half load: the probe stays short, and the table is small enough that the
        // strings it saves outweigh it many times over.
        if (size + 1 > table.length / 2)
        {
            grow();
            index = indexFor(table, value);
        }

        table[index] = value;
        size++;
        return value;
    }

    private void grow()
    {
        final String[] grown = new String[table.length * 2];
        for (final String entry : table)
        {
            if (entry != null)
            {
                grown[indexFor(grown, entry)] = entry;
            }
        }
        table = grown;
    }

    /**
     * Index of {@code value} in {@code target}, or of the free slot it belongs in.
     */
    private static int indexFor(final String[] target, final String value)
    {
        final int mask = target.length - 1;
        int index = spread(value.hashCode()) & mask;
        while (true)
        {
            final String candidate = target[index];
            if (candidate == null || candidate.equals(value))
            {
                return index;
            }
            index = (index + 1) & mask;
        }
    }

    private static int spread(final int hash)
    {
        return hash ^ (hash >>> 16);
    }
}
