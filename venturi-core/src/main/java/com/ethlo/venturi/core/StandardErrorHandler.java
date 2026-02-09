package com.ethlo.venturi.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.api.GatewayErrorHandler;
import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayResponse;
import com.ethlo.venturi.constants.HttpStatuses;
import com.ethlo.venturi.constants.MediaTypes;

public final class StandardErrorHandler implements GatewayErrorHandler
{
    private static final Logger log = LoggerFactory.getLogger(StandardErrorHandler.class);

    @Override
    public void handleError(final GatewayExchange exchange, final Throwable error)
    {
        log.warn("RequestID: {} URI: {} : {}", exchange.requestId(), exchange.request().uri(), error.getMessage(), error);

        final String jsonError = String.format("{\"id\":\"%s\",\"error\":\"%s\"}", exchange.requestId(), "Upstream connection failed");
        final GatewayResponse response = exchange.response();
        if (!response.isCommitted())
        {
            response.setStatus(HttpStatuses.SERVICE_UNAVAILABLE);
            response.localResponse(jsonError.getBytes(), MediaTypes.APPLICATION_JSON);
        }
    }
}