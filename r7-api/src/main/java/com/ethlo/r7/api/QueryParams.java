package com.ethlo.r7.api;

public interface QueryParams
{
    String getFirst(final String name);

    Iterable<String> getAll(final String name);

    /**
     * Calls the consumer with every parameter in order, once per occurrence.
     *
     * @return the number of parameters
     */
    int forEach(EntryConsumer consumer);

    /**
     * @return how many times the parameter occurs. Implementations backed by a collection should
     * override this so a caller can tell a single occurrence apart without copying the values.
     */
    default int count(final String name)
    {
        int count = 0;
        for (final String ignored : getAll(name))
        {
            count++;
        }
        return count;
    }

    default boolean contains(final String name)
    {
        return getFirst(name) != null;
    }

    /**
     * @return the formatted query string, or an empty string if there are no parameters
     */
    String toQueryString();
}