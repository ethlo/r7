package com.ethlo.venturi.api;

public interface MultiAttributes
{
    /**
     * Returns the first header value or null.
     */
    CharSequence getFirst(CharSequence name);

    /**
     * Returns all values for a header.
     * Implementation should return an empty iterable if not found, never null.
     */
    Iterable<CharSequence> getAll(CharSequence name);

    /**
     * Replaces any existing values with this single value.
     */
    void set(CharSequence name, CharSequence value);

    void remove(CharSequence name);

    /**
     * Replaces any existing values with these multiple values.
     */
    void set(CharSequence name, Iterable<CharSequence> values);

    /**
     * Adds a value to any existing values for this header.
     */
    void add(CharSequence name, CharSequence value);

    default boolean contains(CharSequence name)
    {
        return getFirst(name) != null;
    }

    int forEach(EntryConsumer consumer);

    /**
     * Stateful forEach to avoid lambda allocation.
     */
    <S> int forEach(S state, StatefulEntryConsumer<S> consumer);
}
