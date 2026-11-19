package com.ethlo.r7.util;

import com.ethlo.r7.api.MutableQueryParams;

public class MutableQueryParameterImpl extends BaseGatewayAttributes implements MutableQueryParams
{
    protected MutableQueryParameterImpl()
    {
        this(16);
    }

    protected MutableQueryParameterImpl(int initialCapacity)
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

    @Override
    public String toQueryString()
    {
        return "";
    }
}