package com.ethlo.r7.util;

import com.ethlo.r7.api.GatewayHeaders;

/**
 * A standalone header container. Names compare ASCII case-insensitively, as HTTP header names
 * do and as the Undertow-backed view already does, and keep the casing they were stored with
 * for iteration and journaling.
 * <p>
 * Case folding lives here rather than in {@link BaseGatewayAttributes} because that base is
 * shared with {@link FastGatewayAttributes} and {@link MutableQueryParameterImpl}, whose keys
 * (exchange attributes such as {@code gateway.route.id}, and query parameter names) are
 * case-sensitive.
 */
public class FastGatewayHeaders extends BaseGatewayAttributes implements GatewayHeaders
{
    public FastGatewayHeaders(int initialSize)
    {
        super(initialSize);
    }

    public FastGatewayHeaders()
    {
        super(16);
    }

    @Override
    protected boolean keysEqual(final String a, final String b)
    {
        return AsciiCase.equalsIgnoreCase(a, b);
    }

    public static GatewayHeaders empty()
    {
        return new FastGatewayHeaders(0);
    }
}