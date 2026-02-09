package com.ethlo.venturi.core.helpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import com.ethlo.venturi.api.GatewayHeaders;

/**
 * A standalone, memory-resident implementation of GatewayHeaders.
 * Perfect for data read from ClickHouse, Files, or DBs.
 */
public class SimpleGatewayHeaders implements GatewayHeaders
{

    private final Map<String, List<String>> headers;

    public SimpleGatewayHeaders()
    {
        // LinkedHashMap preserves header order for better traceability
        this.headers = new LinkedHashMap<>();
    }

    public SimpleGatewayHeaders(Map<String, List<String>> initialHeaders)
    {
        this.headers = new LinkedHashMap<>(initialHeaders);
    }

    @Override
    public CharSequence getFirst(CharSequence name)
    {
        final List<String> values = headers.get(name.toString());
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }

    @Override
    public Iterable<CharSequence> getAll(CharSequence name)
    {
        final List<String> values = headers.get(name.toString());
        return values != null ? Collections.unmodifiableList(new ArrayList<>(values)) : Collections.emptyList();
    }

    @Override
    public void set(CharSequence name, CharSequence value)
    {
        final List<String> values = new ArrayList<>();
        values.add(value.toString());
        headers.put(name.toString(), values);
    }

    @Override
    public void remove(final CharSequence name)
    {
        headers.remove(name.toString());
    }

    @Override
    public void set(CharSequence name, Iterable<? extends CharSequence> values)
    {
        final List<String> list = new ArrayList<>();
        for (CharSequence v : values)
        {
            list.add(v.toString());
        }
        headers.put(name.toString(), list);
    }

    @Override
    public void addHeader(CharSequence name, CharSequence value)
    {
        headers.computeIfAbsent(name.toString(), k -> new ArrayList<>()).add(value.toString());
    }

    @Override
    public Iterable<CharSequence> names()
    {
        return new ArrayList<>(headers.keySet());
    }

    @Override
    public void forEach(BiConsumer<? super CharSequence, ? super CharSequence> action)
    {
        headers.forEach((name, values) -> {
            for (String value : values)
            {
                action.accept(name, value);
            }
        });
    }

    @Override
    public void forEachGroup(BiConsumer<? super CharSequence, ? super Iterable<CharSequence>> action)
    {
        headers.forEach((name, values) -> action.accept(name, new ArrayList<>(values)));
    }
}