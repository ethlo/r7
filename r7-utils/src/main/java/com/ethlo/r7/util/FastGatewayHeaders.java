package com.ethlo.r7.util;

import com.ethlo.r7.api.GatewayHeaders;

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

    public static GatewayHeaders empty()
    {
        return new FastGatewayHeaders(0);
    }
}