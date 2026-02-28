package com.ethlo.venturi.util;

import com.ethlo.venturi.api.EntryConsumer;
import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.MutableGatewayHeaders;
import com.ethlo.venturi.api.MutableGatewayResponse;
import com.ethlo.venturi.api.StatefulEntryConsumer;

import java.util.Map;

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

    public static MutableBaseGatewayAttributes of(Map<CharSequence, CharSequence> headers)
    {
        final MutableBaseGatewayAttributes h = new MutableBaseGatewayAttributes();
        headers.forEach(h::add);
        return h;
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