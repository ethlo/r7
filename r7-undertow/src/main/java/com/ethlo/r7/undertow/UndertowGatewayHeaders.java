package com.ethlo.r7.undertow;

import java.util.Collections;

import com.ethlo.r7.api.EntryConsumer;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.StatefulEntryConsumer;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.HttpString;

public final class UndertowGatewayHeaders implements MutableGatewayHeaders
{
    private final HeaderMap headerMap;

    public UndertowGatewayHeaders(final HeaderMap headerMap)
    {
        this.headerMap = headerMap;
    }

    @Override
    public String getFirst(final String name)
    {
        return headerMap.getFirst(toHttpString(name));
    }

    @Override
    public Iterable<String> getAll(final String name)
    {
        final HeaderValues values = headerMap.get(toHttpString(name));
        return values != null ? values : Collections.emptyList();
    }

    @Override
    public void add(final String name, final String value)
    {
        headerMap.add(toHttpString(name), value);
    }

    @Override
    public MutableGatewayHeaders set(final String name, final String value)
    {
        headerMap.put(toHttpString(name), value);
        return this;
    }

    @Override
    public void remove(final String name)
    {
        headerMap.remove(toHttpString(name));
    }

    @Override
    public void set(final String name, final Iterable<String> values)
    {
        final HttpString hs = toHttpString(name);
        headerMap.remove(hs);
        for (String v : values)
        {
            headerMap.add(hs, v);
        }
    }

    @Override
    public int forEach(EntryConsumer consumer)
    {
        int totalCount = 0;
        for (HeaderValues values : headerMap)
        {
            final HttpString hs = values.getHeaderName();
            for (String value : values)
            {
                consumer.accept(hs.toString(), value);
                totalCount++;
            }
        }
        return totalCount;
    }

    /**
     * Minimizes HttpString allocations by checking if we already have one.
     */
    private HttpString toHttpString(String name)
    {
        return HttpString.tryFromString(name);
    }

    @Override
    public <S> int forEach(S state, StatefulEntryConsumer<S> consumer)
    {
        int count = 0;
        for (HeaderValues headerValues : headerMap)
        {
            final HttpString hm = headerValues.getHeaderName();
            final String name = hm.toString();
            for (String value : headerValues)
            {
                // Pass the state explicitly
                consumer.accept(state, name, value);
                count++;
            }
        }
        return count;
    }
}