package com.ethlo.venturi.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.api.BeforeUpstreamGatewayExchange;
import com.ethlo.venturi.api.GatewayErrorHandler;
import com.ethlo.venturi.core.proxy.NoAvailableTargetException;
import com.ethlo.venturi.core.proxy.ProxyConnectionException;
import com.ethlo.venturi.core.proxy.ProxyPoolExhaustedException;
import com.ethlo.venturi.util.FastTerminationGatewayResponse;
import com.ethlo.venturi.util.constants.HttpStatuses;
import com.ethlo.venturi.util.constants.MediaTypes;

public final class StandardErrorHandler implements GatewayErrorHandler
{
    private static final Logger log = LoggerFactory.getLogger(StandardErrorHandler.class);

    @Override
    public void handleError(final BeforeUpstreamGatewayExchange exchange, final Throwable error)
    {
        // 1. Determine the correct status and message based on the exception type
        final int status;
        final String userMessage;

        if (error instanceof ProxyPoolExhaustedException)
        {
            // Pool is full - this is a 503 (Temporary)
            status = HttpStatuses.SERVICE_UNAVAILABLE;
            userMessage = "Service temporarily overloaded";
            log.info("RequestID: {} URI: {} : Pool Exhausted", exchange.requestId(), exchange.clientRequest().uri());
        }
        else if (error instanceof NoAvailableTargetException || error instanceof ProxyConnectionException)
        {
            // Connection refused/DNS issues - this is a 502 (Gateway issue)
            status = HttpStatuses.BAD_GATEWAY;
            userMessage = "Upstream connection failed";
            log.info("RequestID: {} URI: {} : Upstream Error - {}", exchange.requestId(), exchange.clientRequest().uri(), error.getMessage());
        }
        else
        {
            // Default for unexpected logic errors
            status = HttpStatuses.INTERNAL_SERVER_ERROR;
            userMessage = "Internal gateway error";
            log.error("RequestID: {} URI: {} : Unexpected Error", exchange.requestId(), exchange.clientRequest().uri(), error);
        }

        // FIXME: Check if we can output an error message or already committed
        // 2. Commit the JSON response
        //final String jsonError = String.format("{\"id\":\"%s\",\"error\":\"%s\"}", exchange.requestId(), userMessage);
        //exchange.terminate(new FastTerminationGatewayResponse(status, MediaTypes.APPLICATION_JSON, ByteBuffer.wrap(jsonError.getBytes(StandardCharsets.UTF_8))));
    }
}