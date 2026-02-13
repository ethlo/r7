package com.ethlo.venturi.core;

import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.api.GatewayErrorHandler;
import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayResponse;
import com.ethlo.venturi.constants.HttpHeaders;
import com.ethlo.venturi.constants.HttpStatuses;
import com.ethlo.venturi.constants.MediaTypes;
import com.ethlo.venturi.core.proxy.NoAvailableTargetException;
import com.ethlo.venturi.core.proxy.ProxyConnectionException;
import com.ethlo.venturi.core.proxy.ProxyPoolExhaustedException;

public final class StandardErrorHandler implements GatewayErrorHandler
{
    private static final Logger log = LoggerFactory.getLogger(StandardErrorHandler.class);

    @Override
    public void handleError(final GatewayExchange exchange, final Throwable error)
    {
        // 1. Determine the correct status and message based on the exception type
        final int status;
        final String userMessage;

        if (error instanceof ProxyPoolExhaustedException)
        {
            // Pool is full - this is a 503 (Temporary)
            status = HttpStatuses.SERVICE_UNAVAILABLE;
            userMessage = "Service temporarily overloaded";
            log.info("RequestID: {} URI: {} : Pool Exhausted", exchange.requestId(), exchange.request().uri());
        }
        else if (error instanceof NoAvailableTargetException || error instanceof ProxyConnectionException)
        {
            // Connection refused/DNS issues - this is a 502 (Gateway issue)
            status = HttpStatuses.BAD_GATEWAY;
            userMessage = "Upstream connection failed";
            log.info("RequestID: {} URI: {} : Upstream Error - {}", exchange.requestId(), exchange.request().uri(), error.getMessage());
        }
        else
        {
            // Default for unexpected logic errors
            status = HttpStatuses.INTERNAL_SERVER_ERROR;
            userMessage = "Internal gateway error";
            log.error("RequestID: {} URI: {} : Unexpected Error", exchange.requestId(), exchange.request().uri(), error);
        }

        // 2. Commit the JSON response
        final String jsonError = String.format("{\"id\":\"%s\",\"error\":\"%s\"}", exchange.requestId(), userMessage);
        final GatewayResponse response = exchange.response();

        if (!response.isCommitted())
        {
            response.status(status);
            response.headers().set(HttpHeaders.CONTENT_TYPE, MediaTypes.APPLICATION_JSON);

            // Add Retry-After if it's a 503 to help clients back off
            if (status == HttpStatuses.SERVICE_UNAVAILABLE)
            {
                response.headers().set(HttpHeaders.RETRY_AFTER, "5");
            }

            response.commitResponse(ByteBuffer.wrap(jsonError.getBytes()));
        }
    }
}