package com.ethlo.r7.util;

import com.ethlo.r7.api.EntryConsumer;
import com.ethlo.r7.api.GatewayHeaders;
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
    public void add(CharSequence name, CharSequence value)
    {
        addInternal(name, value);
    }

    @Override
    public MutableGatewayHeaders set(CharSequence name, CharSequence value)
    {
        setInternal(name, value);
        return null;
    }

    @Override
    public void remove(CharSequence name)
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
    public Iterable<CharSequence> getAll(CharSequence name)
    {
        return getAllInternal(name);
    }

    @Override
    public void set(CharSequence name, Iterable<CharSequence> values)
    {
        removeInternal(name);
        for (CharSequence v : values)
        {
            addInternal(name, v);
        }
    }
}