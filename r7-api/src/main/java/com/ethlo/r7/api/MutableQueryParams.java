package com.ethlo.r7.api;

public interface MutableQueryParams extends QueryParams
{
    /**
     * Replaces all existing values for this parameter with the given value.
     */
    void set(final String name, final String value);

    /**
     * Appends a value to the existing parameter (creates it if it does not exist).
     */
    void add(final String name, final String value);

    /**
     * Removes the parameter entirely.
     */
    void remove(final String name);
}