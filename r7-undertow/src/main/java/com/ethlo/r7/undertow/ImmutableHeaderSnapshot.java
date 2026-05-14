package com.ethlo.r7.undertow;

import com.ethlo.r7.api.EntryConsumer;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.StatefulEntryConsumer;
import com.ethlo.r7.util.ArrayBackedPairStorage;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.HttpString;

public final class ImmutableHeaderSnapshot extends ArrayBackedPairStorage<HttpString, CharSequence> implements GatewayHeaders
{
    public ImmutableHeaderSnapshot(final HeaderMap source)
    {
        // Initialize with source size; pairs are stored as key/value, so source.size() * 2
        super(source.size());

        long cookie = source.fastIterate();
        while (cookie != -1L)
        {
            final HeaderValues headerValues = source.fiCurrent(cookie);
            final HttpString key = headerValues.getHeaderName();

            // We flatten the multi-values into individual pairs to match
            // the ArrayBackedPairStorage linear scan/getAll logic.
            for (String headerValue : headerValues)
            {
                this.addInternal(key, headerValue);
            }
            cookie = source.fiNext(cookie);
        }
    }

    @Override
    protected boolean keysEqual(final HttpString requestedKey, final HttpString storedKey)
    {
        // Optimized check using HttpString's equals
        return storedKey.equals(requestedKey);
    }

    @Override
    public CharSequence getFirst(final CharSequence name)
    {
        return getFirstInternal(new HttpString(name.toString()));
    }

    @Override
    public Iterable<CharSequence> getAll(final CharSequence name)
    {
        return getAllInternal(new HttpString(name.toString()));
    }

    @Override
    public int forEach(final EntryConsumer consumer)
    {
        return forEachInternal(consumer, (c, k, v) -> c.accept(HeaderNameCache.wrap(k), v));
    }

    @Override
    public <S> int forEach(final S state, final StatefulEntryConsumer<S> consumer)
    {
        return forEachInternal(state, (s, k, v) -> consumer.accept(s, HeaderNameCache.wrap(k), v));
    }
}