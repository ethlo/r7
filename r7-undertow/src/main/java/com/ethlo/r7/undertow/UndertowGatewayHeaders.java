package com.ethlo.r7.undertow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.ethlo.r7.api.EntryConsumer;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.StatefulEntryConsumer;
import com.ethlo.r7.api.TextValues;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.HttpString;

/**
 * The header view filters actually mutate in production, backed by Undertow's own
 * {@link HeaderMap}.
 * <p>
 * Mutations are validated with {@link TextValues}, exactly as the standalone containers
 * in {@code r7-utils} are. This is the implementation that matters: a value set here is
 * the one that reaches the wire and the journal, so validating only the other
 * implementations would leave the guarantee true in tests and false in production.
 */
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
        TextValues.requireHeaderName(name);
        TextValues.requireHeaderValue(name, value);
        headerMap.add(toHttpString(name), value);
    }

    @Override
    public MutableGatewayHeaders set(final String name, final String value)
    {
        TextValues.requireHeaderName(name);
        TextValues.requireHeaderValue(name, value);
        headerMap.put(toHttpString(name), value);
        return this;
    }

    @Override
    public void remove(final String name)
    {
        headerMap.remove(toHttpString(name));
    }

    /**
     * Replaces every value for {@code name}. The values are read and validated into a
     * local list before anything is removed, so a rejected value leaves the header map
     * untouched and a single-pass source is iterated only once.
     */
    @Override
    public void set(final String name, final Iterable<String> values)
    {
        TextValues.requireHeaderName(name);
        if (values == null)
        {
            throw new IllegalArgumentException("Values for '" + name + "' must not be null. Use remove(name) instead.");
        }

        final List<String> validated = new ArrayList<>();
        for (final String value : values)
        {
            validated.add(TextValues.requireHeaderValue(name, value));
        }

        final HttpString hs = toHttpString(name);
        headerMap.remove(hs);
        for (final String value : validated)
        {
            headerMap.add(hs, value);
        }
    }

    /**
     * Walks the map with its cookie-based iteration and reads values by index: no iterator object
     * per header, which the request path pays for on every request (the upstream header sanitizer
     * scans every header). Only non-empty entries are visited, which is all the Iterable form ever
     * yielded values for.
     */
    @Override
    public int forEach(EntryConsumer consumer)
    {
        int totalCount = 0;
        for (long cookie = headerMap.fastIterateNonEmpty(); cookie != -1L; cookie = headerMap.fiNextNonEmpty(cookie))
        {
            final HeaderValues values = headerMap.fiCurrent(cookie);
            final String name = values.getHeaderName().toString();
            for (int i = 0, n = values.size(); i < n; i++)
            {
                consumer.accept(name, values.get(i));
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
        for (long cookie = headerMap.fastIterateNonEmpty(); cookie != -1L; cookie = headerMap.fiNextNonEmpty(cookie))
        {
            final HeaderValues values = headerMap.fiCurrent(cookie);
            final String name = values.getHeaderName().toString();
            for (int i = 0, n = values.size(); i < n; i++)
            {
                // Pass the state explicitly
                consumer.accept(state, name, values.get(i));
                count++;
            }
        }
        return count;
    }
}