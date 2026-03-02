package com.ethlo.venturi.util;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.GatewayRequest;

public record ImmutableGatewayRequest(GatewayHeaders headers, CharSequence path, CharSequence uri, CharSequence method,
                                      CharSequence queryParams) implements GatewayRequest
{
}