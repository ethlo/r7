package com.ethlo.venturi.api;

import java.util.StringJoiner;

/**
 * A strongly-typed key for zero-allocation internal state storage.
 */
public final class StateKey<T>
{
    private static final java.util.concurrent.atomic.AtomicInteger ID_GEN = new java.util.concurrent.atomic.AtomicInteger();

    private final String name;
    private final int id;

    public StateKey(String name)
    {
        this.name = name;
        this.id = ID_GEN.getAndIncrement();
    }

    public int id()
    {
        return id;
    }

    public String name()
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