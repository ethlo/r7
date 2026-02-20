package com.ethlo.venturi.undertow;

import java.util.Collections;

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
        // Undertow's getFirst(HttpString) is the fastest path
        return headerMap.getFirst(toHttpString(name));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Iterable<CharSequence> getAll(final CharSequence name)
    {
        final HeaderValues values = headerMap.get(toHttpString(name));
        return values != null ? (Iterable<CharSequence>) (Object) values : Collections.emptyList();
    }

    @Override
    public void add(final CharSequence name, final CharSequence value)
    {
        headerMap.add(toHttpString(name), value.toString());
    }

    @Override
    public void set(final CharSequence name, final CharSequence value)
    {
        headerMap.put(toHttpString(name), value.toString());
    }

    @Override
    public void remove(final CharSequence name)
    {
        headerMap.remove(toHttpString(name));
    }

    @Override
    public void set(final CharSequence name, final Iterable<CharSequence> values)
    {
        final HttpString hs = toHttpString(name);
        headerMap.remove(hs);
        for (CharSequence v : values)
        {
            headerMap.add(hs, v.toString());
        }
    }

    @Override
    public int forEach(EntryConsumer consumer)
    {
        int totalCount = 0;
        for (HeaderValues values : headerMap)
        {
            // Wrap once per key group, not per value
            final HttpString hs = values.getHeaderName();
            final HttpStringCharSequence wrappedName = new HttpStringCharSequence(hs);

            for (String value : values)
            {
                consumer.accept(wrappedName, value);
                totalCount++;
            }
        }
        return totalCount;
    }

    /**
     * Minimizes HttpString allocations by checking if we already have one.
     */
    private HttpString toHttpString(CharSequence name)
    {
        if (name instanceof HttpStringCharSequence wrapper)
        {
            return wrapper.getHttpString();
        }
        // Undertow's tryFromString is optimized for well-known headers
        return HttpString.tryFromString(name.toString());
    }
}