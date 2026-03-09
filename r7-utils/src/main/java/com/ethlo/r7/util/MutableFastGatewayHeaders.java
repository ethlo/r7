package com.ethlo.r7.util;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.MutableGatewayHeaders;

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
    public MutableGatewayHeaders set(final CharSequence name, final CharSequence value)
    {
        setInternal(name, value);
        return this;
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