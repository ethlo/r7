package com.ethlo.venturi.util;

import com.ethlo.venturi.api.GatewayHeaders;

import java.util.concurrent.atomic.AtomicInteger;

public final class FastGatewayHeaders extends ArrayBackedPairStorage<CharSequence, CharSequence> implements GatewayHeaders
{

    public FastGatewayHeaders()
    {
        super(16);
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
    public void add(CharSequence name, CharSequence value)
    {
        addInternal(name, value);
    }

    @Override
    public void set(CharSequence name, CharSequence value)
    {
        setInternal(name, value);
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
    public int weight()
    {
        final AtomicInteger weight = new AtomicInteger(0);
        forEach(weight, (w, name, value) -> w.addAndGet((name.length() + value.length())));
        return weight.get();
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