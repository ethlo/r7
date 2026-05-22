package com.ethlo.r7.util;

import com.ethlo.r7.api.EntryConsumer;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.StatefulEntryConsumer;

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
        addInternal(name, value);
    }

    @Override
    public MutableGatewayHeaders set(String name, String value)
    {
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

    @Override
    public void set(String name, Iterable<String> values)
    {
        removeInternal(name);
        for (String v : values)
        {
            addInternal(name, v);
        }
    }
}