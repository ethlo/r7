package com.ethlo.r7.util;

import java.util.ArrayList;
import java.util.List;

import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.TextValues;

public class MutableFastGatewayHeaders extends FastGatewayHeaders implements MutableGatewayHeaders
{
    public MutableFastGatewayHeaders()
    {
        super(16);
    }

    public MutableFastGatewayHeaders(int initialSize)
    {
        super(initialSize);
    }

    @Override
    public MutableGatewayHeaders set(final String name, final String value)
    {
        TextValues.requireStorableName(name);
        TextValues.requireStorable(name, value);
        setInternal(name, value);
        return this;
    }

    @Override
    public void remove(final String name)
    {
        removeInternal(name);
    }

    /**
     * Replaces every value for {@code name}.
     * <p>
     * The values are read and validated into a local list before anything is mutated, for
     * two reasons: a rejected value must leave the container exactly as it was rather than
     * half-applied, and the source may be single-pass, so it cannot be iterated once to
     * validate and again to store. An empty iterable removes the entry.
     */
    @Override
    public void set(final String name, final Iterable<String> values)
    {
        TextValues.requireStorableName(name);
        if (values == null)
        {
            throw new IllegalArgumentException("Values for '" + name + "' must not be null. Use remove(name) instead.");
        }

        final List<String> validated = new ArrayList<>();
        for (final String value : values)
        {
            validated.add(TextValues.requireStorable(name, value));
        }

        removeInternal(name);
        for (final String value : validated)
        {
            addInternal(name, value);
        }
    }

    @Override
    public void add(final String name, final String value)
    {
        TextValues.requireStorableName(name);
        TextValues.requireStorable(name, value);
        addInternal(name, value);
    }
}