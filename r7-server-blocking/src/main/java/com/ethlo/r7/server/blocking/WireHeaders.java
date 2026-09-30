package com.ethlo.r7.server.blocking;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * Headers as they came off the wire, copied without validating them again. The server's parser
 * has already accepted every name and value; the ISO-8859-1 check exists for values a filter sets,
 * and {@code TextValues} documents keeping it at that point of mutation - which this container
 * still does, since {@link #add}, {@link #set} and friends are inherited unchanged.
 */
public final class WireHeaders extends MutableFastGatewayHeaders
{
    public WireHeaders()
    {
        super(24);
    }

    public void addFromWire(final String name, final String value)
    {
        addInternal(name, value);
    }

    public static WireHeaders copyOf(final GatewayHeaders headers)
    {
        final WireHeaders copy = new WireHeaders();
        headers.forEach(copy, WireHeaders::addFromWire);
        return copy;
    }
}
