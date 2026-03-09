package com.ethlo.r7.api;

/**
 * A high-performance, read-only container for multi-valued keys.
 * <p>
 * Designed for low-allocation traversal using {@link CharSequence} keys and values.
 */
public interface MultiAttributes
{
    /**
     * @return the first value associated with the name, or null if not found
     */
    CharSequence getFirst(CharSequence name);

    /**
     * @return all values for a name; returns an empty iterable if not found
     */
    Iterable<CharSequence> getAll(CharSequence name);

    /**
     * @return true if the name exists
     */
    default boolean contains(CharSequence name)
    {
        return getFirst(name) != null;
    }

    /**
     * Traverses all entries without allocating an Iterator
     */
    int forEach(EntryConsumer consumer);

    /**
     * Stateful traversal to avoid lambda capture allocations
     */
    <S> int forEach(S state, StatefulEntryConsumer<S> consumer);
}