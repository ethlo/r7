package com.ethlo.venturi.util;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.MutableGatewayHeaders;

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

    public static GatewayHeaders empty()
    {
        return new MutableFastGatewayHeaders(0);
    }

    @Override
    public void set(final CharSequence name, final CharSequence value)
    {
        setInternal(name, value);
    }

    @Override
    public void remove(final CharSequence name)
    {
        removeInternal(name);
    }

    @Override
    public void set(final CharSequence name, final Iterable<CharSequence> values)
    {
        // TODO: Implement me
        throw new UnsupportedOperationException();
        //return forEachInternal(this, name, values);
    }

    @Override
    public void add(final CharSequence name, final CharSequence value)
    {
        addInternal(name, value);
    }
}