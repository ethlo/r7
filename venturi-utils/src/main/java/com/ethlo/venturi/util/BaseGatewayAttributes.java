package com.ethlo.venturi.util;

import com.ethlo.venturi.api.EntryConsumer;
import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.StatefulEntryConsumer;

class BaseGatewayAttributes extends ArrayBackedPairStorage<CharSequence, CharSequence> implements GatewayHeaders
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
    protected boolean keysEqual(CharSequence a, CharSequence b)
    {
        return CharSequenceUtil.equals(a, b);
    }

    @Override
    public CharSequence getFirst(CharSequence name)
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
    public Iterable<CharSequence> getAll(CharSequence name)
    {
        return getAllInternal(name);
    }
}