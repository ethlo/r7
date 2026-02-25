package com.ethlo.venturi.api;

import java.util.StringJoiner;

/**
 * A strongly-typed key for zero-allocation internal state storage.
 */
public final class StateKey<T>
{
    private final String name;

    public StateKey(String name)
    {
        this.name = name;
    }

    public String getName()
    {
        return name;
    }

    @Override
    public String toString()
    {
        return new StringJoiner(", ", StateKey.class.getSimpleName() + "[", "]")
                .add("name='" + name + "'")
                .toString();
    }
}