package com.ethlo.r7.api;

/**
 * Provides mutation capabilities for {@link MultiAttributes}.
 */
public interface MutableMultiAttributes
{
    /**
     * Replaces all existing values for the name with a single value
     */
    MutableMultiAttributes set(String name, String value);

    /**
     * Removes all values associated with the name
     */
    void remove(String name);

    /**
     * Replaces all existing values with the provided collection
     */
    void set(String name, Iterable<String> values);

    /**
     * Appends a value to the existing set for the name
     */
    void add(String name, String value);
}