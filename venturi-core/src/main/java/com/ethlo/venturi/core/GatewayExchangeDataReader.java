package com.ethlo.venturi.core;

import java.util.Optional;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.core.model.BodyProvider;

public interface GatewayExchangeDataReader
{
    Optional<GatewayHeaders> getHeaders(final ServerDirection direction, final CharSequence requestId);

    Optional<BodyProvider> getBody(final ServerDirection serverDirection, final CharSequence requestId);
}
