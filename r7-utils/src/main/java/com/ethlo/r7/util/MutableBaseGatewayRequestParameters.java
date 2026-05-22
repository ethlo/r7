package com.ethlo.r7.util;

import com.ethlo.r7.api.MutableQueryParams;

public class MutableBaseGatewayRequestParameters extends BaseGatewayAttributes implements MutableQueryParams
{
    protected MutableBaseGatewayRequestParameters()
    {
        this(16);
    }

    protected MutableBaseGatewayRequestParameters(int initialCapacity)
    {
        super(initialCapacity);
    }

    @Override
    public void add(String name, String value)
    {
        addInternal(name, value);
    }

    @Override
    public void set(String name, String value)
    {
        setInternal(name, value);
    }

    @Override
    public void remove(String name)
    {
        removeInternal(name);
    }

    @Override
    public boolean contains(final String name)
    {
        return getFirst(name) != null;
    }
}