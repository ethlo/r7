package com.ethlo.venturi.undertow;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.ethlo.venturi.api.GatewayErrorHandler;
import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.config.RouteRegistry;
import com.ethlo.venturi.constants.HttpHeaders;
import com.ethlo.venturi.constants.HttpStatuses;
import com.ethlo.venturi.constants.VenturiConstants;
import com.ethlo.venturi.core.AuditLevel;
import com.ethlo.venturi.core.ExecutableRoute;
import com.ethlo.venturi.core.GatewayExchangeDataWriter;
import com.ethlo.venturi.core.RequestIdGenerator;
import com.ethlo.venturi.core.ServerDirection;
import com.ethlo.venturi.core.SortableRequestIdGenerator;
import com.ethlo.venturi.core.StandardErrorHandler;
import com.ethlo.venturi.core.helpers.StartLineBuilder;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;

public final class VenturiUndertowHandler implements HttpHandler
{
    private final GatewayExchangeDataWriter gatewayExchangeDataWriter;
    private final GatewayErrorHandler errorHandler = new StandardErrorHandler();
    private final Executor executor = Executors.newFixedThreadPool(1_000);
    private final RequestIdGenerator requestIdGenerator = new SortableRequestIdGenerator();
    private final RouteRegistry routeRegistry;

    public VenturiUndertowHandler(final RouteRegistry routeRegistry, final GatewayExchangeDataWriter gatewayExchangeDataWriter)
    {
        this.routeRegistry = routeRegistry;
        this.gatewayExchangeDataWriter = gatewayExchangeDataWriter;
    }

    private static void sendNotFound(UndertowGatewayResponse res)
    {
        res.status(HttpStatuses.NOT_FOUND);
        res.headers().set(HttpHeaders.CONTENT_TYPE, "text/plain");
        res.commitResponse(ByteBuffer.wrap("Venturi Server - No route found for request".getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange)
    {
        final UndertowGatewayRequest req = new UndertowGatewayRequest(exchange);
        final Optional<ExecutableRoute> routeOpt = routeRegistry.findRoute(req);
        if (routeOpt.isEmpty())
        {
            // High-speed rejection
            sendNotFound(new UndertowGatewayResponse(exchange));
            return;
        }

        final ExecutableRoute route = routeOpt.get();

        exchange.dispatch(executor, () ->
                {
                    final CharSequence requestId = requestIdGenerator.generate();
                    final UndertowGatewayResponse res = new UndertowGatewayResponse(exchange);
                    final MapGatewayAttributes attrs = new MapGatewayAttributes();
                    final GatewayExchange gatewayExchange = new GatewayExchange(requestId, req, res, attrs, route);
                    handleRoute(route, gatewayExchange);
                }
        );
    }

    private void handleRoute(ExecutableRoute route, GatewayExchange gatewayExchange)
    {
        // Pre-calculate audit flags once per exchange
        final boolean captureReqHeaders = shouldCaptureRequestHeaders(gatewayExchange);
        final boolean captureReqBody = shouldCaptureRequestBody(gatewayExchange);
        final boolean captureResHeaders = shouldCaptureResponseHeaders(gatewayExchange);
        final boolean captureResBody = shouldCaptureResponseBody(gatewayExchange);

        // Capture Request Headers immediately
        if (captureReqHeaders)
        {
            final ByteBuffer startLine = StartLineBuilder.buildRequestLine(gatewayExchange);
            gatewayExchangeDataWriter.begin(ServerDirection.REQUEST, gatewayExchange.requestId(), startLine, gatewayExchange.request().headers());
        }

        // Listener for Request Body
        if (captureReqBody)
        {
            gatewayExchange.request().addBodyListener(buffer -> gatewayExchangeDataWriter.writeBody(ServerDirection.REQUEST, gatewayExchange.requestId(), buffer));
        }

        // Optimized Response Listener
        gatewayExchange.response().addBodyListener(new Consumer<>()
        {
            private boolean headersWritten = false;

            @Override
            public void accept(final ByteBuffer buffer)
            {
                if (!headersWritten && captureResHeaders)
                {
                    final ByteBuffer startLine = StartLineBuilder.buildResponseLine(gatewayExchange);
                    gatewayExchangeDataWriter.begin(ServerDirection.RESPONSE, gatewayExchange.requestId(), startLine, gatewayExchange.response().headers());
                    headersWritten = true;
                }
                if (captureResBody)
                {
                    gatewayExchangeDataWriter.writeBody(ServerDirection.RESPONSE, gatewayExchange.requestId(), buffer);
                }
            }
        });

        try
        {
            route.execute(gatewayExchange);
        }
        catch (Throwable t)
        {
            errorHandler.handleError(gatewayExchange, t);
        }
    }

    private boolean shouldCaptureRequestBody(GatewayExchange gatewayExchange)
    {
        return AuditLevel.FULL == gatewayExchange.attributes().get(VenturiConstants.AUDIT_LEVEL_REQUEST);
    }

    private boolean shouldCaptureResponseBody(GatewayExchange gatewayExchange)
    {
        return AuditLevel.FULL == gatewayExchange.attributes().get(VenturiConstants.AUDIT_LEVEL_RESPONSE);
    }

    private boolean shouldCaptureRequestHeaders(GatewayExchange gatewayExchange)
    {
        final AuditLevel requestedLevel = gatewayExchange.attributes().get(VenturiConstants.AUDIT_LEVEL_REQUEST);
        return AuditLevel.FULL == requestedLevel || AuditLevel.HEADERS == requestedLevel;
    }

    private boolean shouldCaptureResponseHeaders(GatewayExchange gatewayExchange)
    {
        final AuditLevel requestedLevel = gatewayExchange.attributes().get(VenturiConstants.AUDIT_LEVEL_RESPONSE);
        return AuditLevel.FULL == requestedLevel || AuditLevel.HEADERS == requestedLevel;
    }
}