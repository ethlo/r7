package com.ethlo.venturi.undertow;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;

import com.ethlo.venturi.api.GatewayErrorHandler;
import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.config.AuditDefinition;
import com.ethlo.venturi.config.RouteRegistry;
import com.ethlo.venturi.constants.HttpHeaders;
import com.ethlo.venturi.constants.HttpStatuses;
import com.ethlo.venturi.core.AuditLevel;
import com.ethlo.venturi.core.ExecutableRoute;
import com.ethlo.venturi.core.GatewayExchangeDataWriter;
import com.ethlo.venturi.core.RequestIdGenerator;
import com.ethlo.venturi.core.ServerDirection;
import com.ethlo.venturi.core.SortableRequestIdGenerator;
import com.ethlo.venturi.core.helpers.StartLineBuilder;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;

public final class VenturiUndertowHandler implements HttpHandler
{
    static final AttachmentKey<GatewayExchange> GATEWAY_EXCHANGE_KEY = AttachmentKey.create(GatewayExchange.class);

    private final GatewayExchangeDataWriter gatewayExchangeDataWriter;
    private final GatewayErrorHandler errorHandler;
    private final RequestIdGenerator requestIdGenerator = new SortableRequestIdGenerator();
    private final RouteRegistry routeRegistry;

    public VenturiUndertowHandler(final RouteRegistry routeRegistry, final GatewayExchangeDataWriter gatewayExchangeDataWriter, final GatewayErrorHandler errorHandler)
    {
        this.routeRegistry = routeRegistry;
        this.gatewayExchangeDataWriter = gatewayExchangeDataWriter;
        this.errorHandler = errorHandler;
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

        exchange.dispatch(() -> execute(exchange, req, route));
    }

    private void execute(HttpServerExchange exchange, UndertowGatewayRequest req, ExecutableRoute route)
    {
        final CharSequence requestId = requestIdGenerator.generate();
        exchange.addExchangeCompleteListener((ex, next) -> {
            try
            {
                gatewayExchangeDataWriter.complete(requestId);
            } finally
            {
                // Critical
                next.proceed();
            }
        });

        final UndertowGatewayResponse res = new UndertowGatewayResponse(exchange);
        final MapGatewayAttributes attrs = new MapGatewayAttributes();
        final GatewayExchange gatewayExchange = new GatewayExchange(requestId, req, res, attrs, route);

        exchange.putAttachment(GATEWAY_EXCHANGE_KEY, gatewayExchange);

        handleRoute(route, gatewayExchange);
    }

    private void handleRoute(ExecutableRoute route, GatewayExchange gatewayExchange)
    {
        setupAuditing(gatewayExchange, route.routeDefinition().audit());

        try
        {
            route.execute(gatewayExchange);
        }
        catch (Throwable t)
        {
            // If everything fails before the proxy jump
            errorHandler.handleError(gatewayExchange, t);
        }
    }

    private void setupAuditing(GatewayExchange gatewayExchange, final AuditDefinition audit)
    {
        final CharSequence requestId = gatewayExchange.requestId();

        // Capture Request Headers immediately
        if (shouldCaptureRequestHeaders(audit))
        {
            final ByteBuffer startLine = StartLineBuilder.buildRequestLine(gatewayExchange);
            gatewayExchangeDataWriter.begin(ServerDirection.REQUEST, requestId, startLine, gatewayExchange.request().headers());
        }

        // Attach Request Body Listener
        if (shouldCaptureRequestBody(audit))
        {
            gatewayExchange.request().addBodyListener(buffer ->
                    gatewayExchangeDataWriter.writeBody(ServerDirection.REQUEST, requestId, buffer));
        }

        // Attach Response Listener (Headers + Body)
        final boolean captureResHeaders = shouldCaptureResponseHeaders(audit);
        final boolean captureResBody = shouldCaptureResponseBody(audit);

        if (captureResHeaders || captureResBody)
        {
            gatewayExchange.response().addBodyListener(new Consumer<>()
            {
                private boolean headersWritten = false;

                @Override
                public void accept(final ByteBuffer buffer)
                {
                    if (!headersWritten && captureResHeaders)
                    {
                        final ByteBuffer startLine = StartLineBuilder.buildResponseLine(gatewayExchange);
                        gatewayExchangeDataWriter.begin(ServerDirection.RESPONSE, requestId, startLine, gatewayExchange.response().headers());
                        headersWritten = true;
                    }
                    if (captureResBody)
                    {
                        gatewayExchangeDataWriter.writeBody(ServerDirection.RESPONSE, requestId, buffer);
                    }
                }
            });
        }
    }

    private boolean shouldCaptureRequestBody(AuditDefinition auditDefinition)
    {
        return AuditLevel.FULL == auditDefinition.request;
    }

    private boolean shouldCaptureResponseBody(AuditDefinition auditDefinition)
    {
        return AuditLevel.FULL == auditDefinition.request;
    }

    private boolean shouldCaptureRequestHeaders(AuditDefinition auditDefinition)
    {
        return AuditLevel.FULL == auditDefinition.request || AuditLevel.HEADERS == auditDefinition.request;
    }

    private boolean shouldCaptureResponseHeaders(AuditDefinition auditDefinition)
    {
        return AuditLevel.FULL == auditDefinition.response || AuditLevel.HEADERS == auditDefinition.response;
    }
}