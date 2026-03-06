package com.ethlo.venturi.util;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.api.IpSource;

import java.net.InetAddress;

public record ImmutableGatewayRequest(GatewayHeaders headers, CharSequence path, CharSequence uri, CharSequence method,
                                      CharSequence queryParams, InetAddress remoteAddress, IpSource ipSource) implements GatewayRequest
{
}