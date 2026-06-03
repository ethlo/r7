package com.ethlo.r7.undertow;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import com.ethlo.r7.api.MutableQueryParams;
import io.undertow.server.HttpServerExchange;

public final class UndertowMutableQueryParams implements MutableQueryParams
{
    private final HttpServerExchange exchange;

    public UndertowMutableQueryParams(final HttpServerExchange exchange)
    {
        this.exchange = exchange;
    }

    @Override
    public void set(final String name, final String value)
    {
        final Deque<String> deque = new ArrayDeque<>(1);
        deque.add(value);
        this.exchange.getQueryParameters().put(name, deque);
        this.syncQueryString();
    }

    @Override
    public void add(final String name, final String value)
    {
        this.exchange.getQueryParameters().computeIfAbsent(name, k -> new ArrayDeque<>()).add(value);
        this.syncQueryString();
    }

    @Override
    public void remove(final String name)
    {
        this.exchange.getQueryParameters().remove(name);
        this.syncQueryString();
    }

    @Override
    public String getFirst(final String name)
    {
        final Deque<String> values = this.exchange.getQueryParameters().get(name);
        if (values != null && !values.isEmpty())
        {
            return values.getFirst();
        }
        return null;
    }

    @Override
    public List<String> getAll(final String name)
    {
        final Deque<String> values = this.exchange.getQueryParameters().get(name);
        if (values != null)
        {
            return List.copyOf(values);
        }
        return List.of();
    }

    @Override
    public boolean contains(final String name)
    {
        return this.exchange.getQueryParameters().containsKey(name);
    }

    @Override
    public String toQueryString()
    {
        return this.exchange.getQueryString();
    }

    private void syncQueryString()
    {
        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, Deque<String>> entry : this.exchange.getQueryParameters().entrySet())
        {
            for (final String value : entry.getValue())
            {
                if (!sb.isEmpty())
                {
                    sb.append('&');
                }
                sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
                if (value != null && !value.isEmpty())
                {
                    sb.append('=');
                    sb.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
                }
            }
        }
        this.exchange.setQueryString(sb.toString());
    }
}