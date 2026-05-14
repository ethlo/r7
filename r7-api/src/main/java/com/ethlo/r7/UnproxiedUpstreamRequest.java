package com.ethlo.r7;

import java.net.InetAddress;
import java.util.List;

import com.ethlo.r7.api.EntryConsumer;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.MutableGatewayRequest;
import com.ethlo.r7.api.StatefulEntryConsumer;

/**
 * A sentinel implementation of {@link MutableGatewayRequest} representing a request
 * that was never dispatched to an upstream service.
 * <p>
 * This object is used in the response and completion phases when the exchange was
 * short-circuited or terminated before the proxy handler was invoked.
 */
public final class UnproxiedUpstreamRequest implements MutableGatewayRequest
{
    /**
     * The singleton instance representing a skipped or aborted upstream request.
     */
    public static final UnproxiedUpstreamRequest INSTANCE = new UnproxiedUpstreamRequest();
    MutableGatewayHeaders EMPTY_HEADERS = new MutableGatewayHeaders()
    {
        @Override
        public MutableGatewayHeaders set(final CharSequence name, final CharSequence value)
        {
            return this;
        }

        @Override
        public void remove(final CharSequence name)
        {

        }

        @Override
        public void set(final CharSequence name, final Iterable<CharSequence> values)
        {

        }

        @Override
        public void add(final CharSequence name, final CharSequence value)
        {

        }

        @Override
        public CharSequence getFirst(final CharSequence name)
        {
            return null;
        }

        @Override
        public Iterable<CharSequence> getAll(final CharSequence name)
        {
            return List.of();
        }

        @Override
        public int forEach(final EntryConsumer consumer)
        {
            return 0;
        }

        @Override
        public <S> int forEach(final S state, final StatefulEntryConsumer<S> consumer)
        {
            return 0;
        }
    };

    private UnproxiedUpstreamRequest()
    {
    }

    /**
     * @return an empty string as no upstream method was called
     */
    @Override
    public String method()
    {
        return "";
    }

    /**
     * @return an empty string as no upstream URI was targeted
     */
    @Override
    public String uri()
    {
        return "";
    }

    @Override
    public CharSequence path()
    {
        return "";
    }

    @Override
    public CharSequence queryParams()
    {
        return "";
    }

    /**
     * @return an empty, immutable headers container
     */
    @Override
    public MutableGatewayHeaders headers()
    {
        return EMPTY_HEADERS;
    }

    /**
     * No-op: Mutations are ignored on a non-existent upstream request
     */
    @Override
    public void path(final CharSequence newPath)
    {
    }

    @Override
    public void queryParams(final CharSequence newQueryParams)
    {
    }

    @Override
    public void uri(final CharSequence uri)
    {
    }

    @Override
    public void method(final CharSequence method)
    {
    }

    /**
     * @return null, as no network connection to a backend was established
     */
    @Override
    public InetAddress remoteAddress()
    {
        return null;
    }
}