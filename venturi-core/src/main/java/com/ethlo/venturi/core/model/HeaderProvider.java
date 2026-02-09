package com.ethlo.venturi.core.model;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.core.DataBufferRepository;
import com.ethlo.venturi.core.ServerDirection;
import com.ethlo.venturi.core.helpers.SimpleGatewayHeaders;

/**
 * Provides a unified way to access request/response headers,
 * regardless of whether they are in memory or need to be read from disk.
 */
public class HeaderProvider {
    private final DataBufferRepository repository;
    private final String requestId;
    private final ServerDirection serverDirection;
    private GatewayHeaders cache;

    public HeaderProvider(final DataBufferRepository repository, final String requestId, final ServerDirection serverDirection) {
        this.repository = repository;
        this.requestId = requestId;
        this.serverDirection = serverDirection;
    }

    public GatewayHeaders getHeaders() {
        if (cache == null) {
            cache = repository.getHeaders(serverDirection, requestId).orElseGet(SimpleGatewayHeaders::new);
        }
        return cache;
    }
}