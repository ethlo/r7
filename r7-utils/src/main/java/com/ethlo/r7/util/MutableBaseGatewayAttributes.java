package com.ethlo.r7.util;

import java.util.ArrayList;
import java.util.List;

import com.ethlo.r7.api.EntryConsumer;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.StatefulEntryConsumer;
import com.ethlo.r7.api.TextValues;

class MutableBaseGatewayAttributes extends BaseGatewayAttributes implements MutableGatewayHeaders
{
    protected MutableBaseGatewayAttributes()
    {
        this(16);
    }

    protected MutableBaseGatewayAttributes(int initialCapacity)
    {
        super(initialCapacity);
    }

    @Override
    public void add(String name, String value)
    {
        TextValues.requireStorableName(name);
        TextValues.requireStorable(name, value);
        addInternal(name, value);
    }

    @Override
    public MutableGatewayHeaders set(String name, String value)
    {
        TextValues.requireStorableName(name);
        TextValues.requireStorable(name, value);
        setInternal(name, value);
        return this;
    }

    @Override
    public void remove(String name)
    {
        removeInternal(name);
    }

    @Override
    public int forEach(EntryConsumer consumer)
    {
        return forEachInternal(consumer::accept);
    }

    @Override
    public <S> int forEach(final S state, final StatefulEntryConsumer<S> consumer)
    {
        return forEachInternal(state, consumer::accept);
    }

    @Override
    public Iterable<String> getAll(String name)
    {
        return getAllInternal(name);
    }

    /**
     * Replaces every value for {@code name}. The values are read and validated into a
     * local list first, so that a rejected value leaves the container unchanged and a
     * single-pass source is only iterated once. An empty iterable removes the entry.
     */
    @Override
    public void set(String name, Iterable<String> values)
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
}