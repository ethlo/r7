package com.ethlo.venturi.api;

public interface MutableMultiAttributes
{
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

}
