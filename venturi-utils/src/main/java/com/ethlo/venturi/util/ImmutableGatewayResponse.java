package com.ethlo.venturi.util;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.GatewayResponse;

public record ImmutableGatewayResponse(GatewayHeaders headers, int status,
                                       boolean isCommitted) implements GatewayResponse
{
}