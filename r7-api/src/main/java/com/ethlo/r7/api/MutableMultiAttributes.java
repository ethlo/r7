package com.ethlo.r7.api;

/**
 * Provides mutation capabilities for {@link MultiAttributes}.
 */
public interface MutableMultiAttributes
{
    /**
     * Replaces all existing values for the name with a single value
     */
    MutableMultiAttributes set(CharSequence name, CharSequence value);

    /**
     * Removes all values associated with the name
     */
    void remove(CharSequence name);

    /**
     * Replaces all existing values with the provided collection
     */
    void set(CharSequence name, Iterable<CharSequence> values);

    /**
     * Appends a value to the existing set for the name
     */
    void add(CharSequence name, CharSequence value);
}