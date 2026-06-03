package com.ethlo.r7.undertow;

import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import com.ethlo.r7.api.QueryParams;

public final class UndertowQueryParams implements QueryParams
{
    private final Map<String, Deque<String>> parameters;
    private final String queryString;

    public UndertowQueryParams(String queryString, final Map<String, Deque<String>> parameters)
    {
        this.queryString = queryString;
        this.parameters = parameters;
    }

    @Override
    public String getFirst(final String name)
    {
        final Deque<String> values = this.parameters.get(name);
        if (values != null && !values.isEmpty())
        {
            return values.getFirst();
        }
        return null;
    }

    @Override
    public List<String> getAll(final String name)
    {
        final Deque<String> values = this.parameters.get(name);
        if (values != null)
        {
            return List.copyOf(values);
        }
        return Collections.emptyList();
    }

    @Override
    public boolean contains(final String name)
    {
        return this.parameters.containsKey(name);
    }

    @Override
    public String toQueryString()
    {
        return queryString;
    }
}