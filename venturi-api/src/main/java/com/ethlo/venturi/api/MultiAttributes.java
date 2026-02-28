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
