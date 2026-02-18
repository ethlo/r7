package com.ethlo.venturi.undertow;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.ethlo.venturi.ShardedJournalWriter;
import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayErrorHandler;
import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.ServerDirection;
import com.ethlo.venturi.config.AuditDefinition;
import com.ethlo.venturi.config.RouteRegistry;
import com.ethlo.venturi.constants.HttpHeaders;
import com.ethlo.venturi.constants.HttpStatuses;
import com.ethlo.venturi.core.AuditLevel;
import com.ethlo.venturi.core.ExecutableRoute;
import com.ethlo.venturi.core.FastGatewayAttributes;
import com.ethlo.venturi.core.RequestIdGenerator;
import com.ethlo.venturi.core.SortableRequestIdGenerator;
import com.ethlo.venturi.core.helpers.StartLineBuilder;
import com.ethlo.venturi.vlf.VlfJournal;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;

public final class VenturiUndertowHandler implements HttpHandler
{
    static final CharSequence JOURNAL_KEY = ".JOURNAL";
    static final CharSequence AUDIT_CONFIG_KEY = ".AUDIT_CONFIG";
    static final CharSequence REQUEST_START_NANOS_KEY = ".REQUEST_START_NANOS";
    private static final CharSequence ROUTE_ID_KEY = ".ROUTE_ID";
    public static final AttachmentKey<GatewayExchange> GATEWAY_EXCHANGE_KEY = AttachmentKey.create(GatewayExchange.class);

    private final GatewayErrorHandler errorHandler;
    private final RequestIdGenerator requestIdGenerator = new SortableRequestIdGenerator();
    private final RouteRegistry routeRegistry;
    private final ShardedJournalWriter<VlfJournal> gatewayExchangeDataWriter;

    public VenturiUndertowHandler(final RouteRegistry routeRegistry, final ShardedJournalWriter<VlfJournal> gatewayExchangeDataWriter, final GatewayErrorHandler errorHandler)
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
        final long startNanos = System.nanoTime();
        final UndertowGatewayRequest req = new UndertowGatewayRequest(exchange);
        final Optional<ExecutableRoute> routeOpt = routeRegistry.findRoute(req);
        if (routeOpt.isEmpty())
        {
            sendNotFound(new UndertowGatewayResponse(exchange));
            return;
        }

        final ExecutableRoute route = routeOpt.get();
        execute(exchange, req, route, startNanos);
    }

    private void execute(HttpServerExchange exchange, UndertowGatewayRequest req, ExecutableRoute route, final long startNanos)
    {
        final CharSequence requestId = requestIdGenerator.generate();
        final UndertowGatewayResponse res = new UndertowGatewayResponse(exchange);
        final GatewayAttributes attrs = new FastGatewayAttributes();
        final GatewayExchange gatewayExchange = new GatewayExchange(requestId, req, res, attrs, route);

        exchange.putAttachment(GATEWAY_EXCHANGE_KEY, gatewayExchange);
        attrs.put(REQUEST_START_NANOS_KEY, startNanos);
        attrs.put(AUDIT_CONFIG_KEY, route.routeDefinition().audit());
        attrs.put(ROUTE_ID_KEY, route.id());

        setupBinaryLogging(exchange, gatewayExchange);

        handleRoute(route, gatewayExchange);
    }

    private void setupBinaryLogging(HttpServerExchange exchange, GatewayExchange gatewayExchange)
    {
        final AuditDefinition audit = gatewayExchange.attributes().get(AUDIT_CONFIG_KEY);
        if (audit == null)
        {
            return;
        }

        // Track request bytes
        final AtomicLong requestBytesRead = new AtomicLong(0);
        exchange.addRequestWrapper((factory, ex) -> new ByteCountingStreamSourceConduit(factory.create(), requestBytesRead));

        // Pin the journal to this specific request lifecycle
        final CharSequence requestId = gatewayExchange.requestId();
        final VlfJournal requestJournal = gatewayExchangeDataWriter.getJournal(requestId);
        gatewayExchange.attributes().put(JOURNAL_KEY, requestJournal);

        // Add Completion Listener using the pinned journal
        exchange.addExchangeCompleteListener((ex, next) -> {
            try
            {
                final VlfJournal journal = gatewayExchange.attributes().get(JOURNAL_KEY);
                if (journal != null)
                {
                    final long startNanos = gatewayExchange.attributes().get(REQUEST_START_NANOS_KEY);
                    synchronized (journal)
                    {
                        journal.end(requestId, ex.getStatusCode(), ex.getResponseBytesSent(), requestBytesRead.get(), System.nanoTime() - startNanos);
                    }
                }
            } finally
            {
                next.proceed();
            }
        });

        final VlfJournal journal = gatewayExchange.attributes().get(JOURNAL_KEY);

        if (journal == null)
        {
            return;
        }

        // Always capture Request Headers for access log
        final ByteBuffer startLine = StartLineBuilder.buildRequestLine(gatewayExchange);
        synchronized (journal)
        {
            journal.start(ServerDirection.REQUEST, requestId, startLine, gatewayExchange.request().headers());
        }

        // Capture Request Body
        if (shouldCaptureRequestBody(audit))
        {
            gatewayExchange.request().addBodyListener(buffer -> {
                synchronized (journal)
                {
                    journal.body(ServerDirection.REQUEST, requestId, buffer);
                }
            });
        }

        final boolean captureResBody = shouldCaptureResponseBody(audit);

        if (shouldCaptureResponseHeaders(audit) || shouldCaptureResponseBody(audit))
        {
            gatewayExchange.response().addBodyListener(new Consumer<>()
            {
                private boolean headersWritten = false;

                @Override
                public void accept(final ByteBuffer buffer)
                {
                    synchronized (journal)
                    {
                        if (!headersWritten)
                        {
                            final ByteBuffer startLine = StartLineBuilder.buildResponseLine(gatewayExchange);
                            journal.start(ServerDirection.RESPONSE, requestId, startLine, gatewayExchange.response().headers());
                            headersWritten = true;
                        }
                        if (captureResBody)
                        {
                            journal.body(ServerDirection.RESPONSE, requestId, buffer);
                        }
                    }
                }
            });
        }
    }

    private void handleRoute(ExecutableRoute route, GatewayExchange gatewayExchange)
    {
        try
        {
            route.execute(gatewayExchange);
        }
        catch (Throwable t)
        {
            errorHandler.handleError(gatewayExchange, t);
        }
    }

    private boolean shouldCaptureRequestBody(AuditDefinition auditDefinition)
    {
        return AuditLevel.FULL == auditDefinition.request;
    }

    private boolean shouldCaptureResponseBody(AuditDefinition auditDefinition)
    {
        return AuditLevel.FULL == auditDefinition.response;
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