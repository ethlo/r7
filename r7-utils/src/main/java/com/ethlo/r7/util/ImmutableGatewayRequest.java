package com.ethlo.r7.util;

import java.net.InetAddress;

import com.ethlo.r7.api.Cookies;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.api.QueryParams;

public record ImmutableGatewayRequest(GatewayHeaders headers, String path, String uri, String method,
                                      QueryParams queryParams, Cookies cookies, InetAddress remoteAddress,
                                      IpSource ipSource) implements GatewayRequest
{
}