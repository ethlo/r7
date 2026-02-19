package com.ethlo.venturi.undertow;

import java.util.Collections;
import java.util.Iterator;
import java.util.function.BiConsumer;

import com.ethlo.venturi.api.GatewayHeaders;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.HttpString;

public final class UndertowGatewayHeaders implements GatewayHeaders
{
    private final HeaderMap headerMap;

    public UndertowGatewayHeaders(final HeaderMap headerMap)
    {
        this.headerMap = headerMap;
    }

    @Override
    public CharSequence getFirst(final CharSequence name)
    {
        // Undertow optimized path
        return headerMap.getFirst(name.toString());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Iterable<CharSequence> getAll(final CharSequence name)
    {
        final HeaderValues values = headerMap.get(name.toString());
        if (values == null)
        {
            return Collections.emptyList();
        }
        // Zero-copy cast: HeaderValues implements Iterable<String>, String is CharSequence
        return (Iterable<CharSequence>) (Object) values;
    }

    @Override
    public Iterable<CharSequence> names()
    {
        return () -> new Iterator<>()
        {
            private final Iterator<HttpString> delegate = headerMap.getHeaderNames().iterator();

            @Override
            public boolean hasNext()
            {
                return delegate.hasNext();
            }

            @Override
            public CharSequence next()
            {
                return new HttpStringCharSequence(delegate.next());
            }
        };
    }

    @Override
    public void add(final CharSequence name, final CharSequence value)
    {
        headerMap.add(HttpString.tryFromString(name.toString()), value.toString());
    }

    @Override
    public void set(final CharSequence name, final CharSequence value)
    {
        headerMap.put(HttpString.tryFromString(name.toString()), value.toString());
    }

    @Override
    public void remove(final CharSequence name)
    {
        headerMap.remove(HttpString.tryFromString(name.toString()));
    }

    @Override
    public void set(final CharSequence name, final Iterable<? extends CharSequence> values)
    {
        final HttpString hs = HttpString.tryFromString(name.toString());
        headerMap.remove(hs);
        for (CharSequence v : values)
        {
            headerMap.add(hs, v.toString());
        }
    }

    @Override
    public void forEach(BiConsumer<? super CharSequence, ? super CharSequence> action)
    {
        for (HeaderValues values : headerMap)
        {
            final HttpStringCharSequence nameWrapper = new HttpStringCharSequence(values.getHeaderName());
            for (String value : values)
            {
                action.accept(nameWrapper, value);
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void forEachGroup(BiConsumer<? super CharSequence, ? super Iterable<CharSequence>> action)
    {
        for (HeaderValues values : headerMap)
        {
            action.accept(values.getHeaderName().toString(), (Iterable<CharSequence>) (Object) values);
        }
    }
}