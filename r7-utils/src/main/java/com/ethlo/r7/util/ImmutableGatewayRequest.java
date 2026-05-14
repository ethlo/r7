package com.ethlo.r7.util;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.IpSource;

import java.net.InetAddress;

public record ImmutableGatewayRequest(GatewayHeaders headers, CharSequence path, CharSequence uri, CharSequence method,
                                      CharSequence queryParams, InetAddress remoteAddress, IpSource ipSource) implements GatewayRequest
{
}