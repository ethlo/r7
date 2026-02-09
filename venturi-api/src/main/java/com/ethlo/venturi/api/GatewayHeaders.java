package com.ethlo.venturi.api;

import java.util.function.BiConsumer;

public interface GatewayHeaders
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
    void set(CharSequence name, Iterable<? extends CharSequence> values);

    /**
     * Adds a value to any existing values for this header.
     */
    void addHeader(CharSequence name, CharSequence value);

    /**
     * Iterates over every single Name-Value pair.
     * Useful for simple logging or header-by-header transformations.
     */
    default void forEach(BiConsumer<? super CharSequence, ? super CharSequence> action)
    {
        for (CharSequence name : names())
        {
            for (CharSequence value : getAll(name))
            {
                action.accept(name, value);
            }
        }
    }

    /**
     * Iterates over Names and their associated List of values.
     * Efficient for proxying where you want to copy all values at once.
     */
    default void forEachGroup(BiConsumer<? super CharSequence, ? super Iterable<CharSequence>> action)
    {
        for (CharSequence name : names())
        {
            action.accept(name, getAll(name));
        }
    }

    Iterable<CharSequence> names();

    default boolean contains(CharSequence name)
    {
        return getFirst(name) != null;
    }
}