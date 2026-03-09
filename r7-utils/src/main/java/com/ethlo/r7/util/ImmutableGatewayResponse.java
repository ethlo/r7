package com.ethlo.r7.util;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.GatewayResponse;

public record ImmutableGatewayResponse(GatewayHeaders headers, int status,
                                       boolean isCommitted) implements GatewayResponse
{
}