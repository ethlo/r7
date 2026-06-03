package com.ethlo.r7.api;

public interface QueryParams
{
    String getFirst(final String name);

    Iterable<String> getAll(final String name);

    default boolean contains(final String name)
    {
        return getFirst(name) != null;
    }

    /**
     * @return the formatted query string, or an empty string if there are no parameters
     */
    String toQueryString();
}