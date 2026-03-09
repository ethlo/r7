package com.ethlo.venturi;

import com.ethlo.venturi.api.MutableGatewayHeaders;
import com.ethlo.venturi.api.MutableGatewayRequest;

import java.net.InetAddress;

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
        return MutableGatewayHeaders.EMPTY;
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