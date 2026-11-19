package com.ethlo.r7.util;

import java.util.Objects;

import com.ethlo.r7.api.EntryConsumer;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.StatefulEntryConsumer;

class BaseGatewayAttributes extends ArrayBackedPairStorage<String, String> implements GatewayHeaders
{
    protected BaseGatewayAttributes()
    {
        this(16);
    }

    protected BaseGatewayAttributes(int initialCapacity)
    {
        super(initialCapacity);
    }

    @Override
    protected boolean keysEqual(String a, String b)
    {
        return Objects.equals(a, b);
    }

    @Override
    public String getFirst(String name)
    {
        return getFirstInternal(name);
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
}