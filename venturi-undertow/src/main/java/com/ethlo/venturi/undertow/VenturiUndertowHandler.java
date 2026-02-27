package com.ethlo.venturi.undertow;

import static com.ethlo.venturi.journal.api.JournalLevel.FULL;
import static com.ethlo.venturi.journal.api.JournalLevel.METADATA;
import static com.ethlo.venturi.journal.api.JournalLevel.NONE;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.ethlo.venturi.ShardedJournalWriter;
import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayErrorHandler;
import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;
import com.ethlo.venturi.api.StateKey;
import com.ethlo.venturi.config.RouteJournalConfig;
import com.ethlo.venturi.config.RouteRegistry;
import com.ethlo.venturi.core.ExecutableRoute;
import com.ethlo.venturi.core.RequestIdGenerator;
import com.ethlo.venturi.core.SortableRequestIdGenerator;
import com.ethlo.venturi.core.helpers.StartLineBuilder;
import com.ethlo.venturi.journal.api.Journal;
import com.ethlo.venturi.journal.api.JournalLevel;
import com.ethlo.venturi.util.FastGatewayAttributes;
import com.ethlo.venturi.util.FastGatewayHeaders;
import com.ethlo.venturi.util.constants.HttpHeaders;
import com.ethlo.venturi.util.constants.HttpStatuses;
import com.ethlo.venturi.vlf.VlfJournal;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;

public final class VenturiUndertowHandler implements HttpHandler
{
    public static final AttachmentKey<GatewayExchange> GATEWAY_EXCHANGE_KEY = AttachmentKey.create(GatewayExchange.class);

    static final StateKey<Journal> JOURNAL_KEY = new StateKey<>(".JOURNAL");
    static final StateKey<RouteJournalConfig> ROUTE_JOURNAL_CONFIG_KEY = new StateKey<>(".AUDIT_CONFIG");
    static final StateKey<Long> REQUEST_START_NANOS_KEY = new StateKey<>(".REQUEST_START_NANOS");
    static final StateKey<LongSupplier> REQUEST_BYTES_READ_KEY = new StateKey<>(".REQUEST_BYTES_READ");
    static final StateKey<LongSupplier> RESPONSE_BYTES_SENT_KEY = new StateKey<>(".RESPONSE_BYTES_READ");
    static final StateKey<BiConsumer<GatewayExchange, ByteBuffer>> PRE_ROUTING_COMMIT_LISTENER_KEY = new StateKey<>(".PRE_ROUTING_COMMIT_LISTENER");

    private static final CharSequence ROUTE_ID_KEY = "route_id";
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

    private static void startResponse(GatewayExchange gatewayExchange, Journal journal, RouteJournalConfig journalConfig, CharSequence requestId)
    {
        final ByteBuffer startLine = StartLineBuilder.buildResponseLine(gatewayExchange);
        if (journalConfig.response() == METADATA)
        {
            journal.start(ServerDirection.RESPONSE, journalConfig.response(), requestId, startLine, FastGatewayHeaders.empty());
        }
        else
        {
            journal.start(ServerDirection.RESPONSE, journalConfig.response(), requestId, startLine, gatewayExchange.response().headers());
        }
    }

    private static void filterStartRequest(Journal journal, GatewayExchange gatewayExchange, RouteJournalConfig journalConfig, CharSequence requestId, ByteBuffer startLine)
    {
        if (journalConfig.request() == METADATA)
        {
            // Strip headers
            journal.start(ServerDirection.REQUEST, journalConfig.request(), requestId, startLine, FastGatewayHeaders.empty());
        }
        else
        {
            // Keep headers
            journal.start(ServerDirection.REQUEST, journalConfig.request(), requestId, startLine, filterRequestHeaders(gatewayExchange.request().headers()));
        }
    }

    private static GatewayHeaders filterRequestHeaders(GatewayHeaders headers)
    {
        // TODO: Add filtering and redaction
        return headers;
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
        final GatewayExchange gatewayExchange = new UndertowGatewayExchange(exchange, requestId, req, res, attrs, route);

        exchange.putAttachment(GATEWAY_EXCHANGE_KEY, gatewayExchange);
        gatewayExchange.putInternalState(REQUEST_START_NANOS_KEY, startNanos);
        final RouteJournalConfig journalConfig = route.routeDefinition().journal();
        gatewayExchange.putInternalState(ROUTE_JOURNAL_CONFIG_KEY, journalConfig);
        gatewayExchange.attributes().add(ROUTE_ID_KEY, route.id());

        final VlfJournal journal = gatewayExchangeDataWriter.getJournal(requestId);
        setupJournaling(journal, exchange, gatewayExchange);

        gatewayExchange.putInternalState(PRE_ROUTING_COMMIT_LISTENER_KEY, (e, body) ->
                {
                    startResponse(e, journal, journalConfig, requestId);
                    if (journalConfig.response() == FULL)
                    {
                        journal.body(ServerDirection.RESPONSE, requestId, body);
                    }
                }
        );

        handleRoute(route, gatewayExchange);
    }

    private void setupJournaling(Journal journal, HttpServerExchange exchange, GatewayExchange gatewayExchange)
    {
        final RouteJournalConfig journalConfig = gatewayExchange.getInternalState(ROUTE_JOURNAL_CONFIG_KEY);
        if (journalConfig == null || (journalConfig.request() == NONE && journalConfig.response() == NONE))
        {
            return;
        }

        final CharSequence requestId = gatewayExchange.requestId();
        gatewayExchange.putInternalState(JOURNAL_KEY, journal);

        if (shouldCaptureRequestHeaders(journalConfig))
        {
            final ByteBuffer startLine = StartLineBuilder.buildRequestLine(gatewayExchange);
            filterStartRequest(journal, gatewayExchange, journalConfig, requestId, startLine);
        }

        // Track request bytes
        final AtomicLong requestBytesRead = new AtomicLong(0);
        exchange.addRequestWrapper((factory, ex) -> new ByteCountingStreamSourceConduit(factory.create(), requestBytesRead));
        gatewayExchange.putInternalState(REQUEST_BYTES_READ_KEY, requestBytesRead::get);

        // Track response bytes
        gatewayExchange.putInternalState(RESPONSE_BYTES_SENT_KEY, exchange::getResponseBytesSent);

        // Capture Request Body
        if (shouldCaptureRequestBody(journalConfig))
        {
            gatewayExchange.request().addBodyListener(buffer
                    -> journal.body(ServerDirection.REQUEST, requestId, buffer));
        }

        final boolean captureResBody = shouldCaptureResponseBody(journalConfig);

        if (shouldCaptureResponseHeaders(journalConfig) || shouldCaptureResponseBody(journalConfig))
        {
            gatewayExchange.response().addBodyListener(new Consumer<>()
            {
                private boolean headersWritten = false;

                @Override
                public void accept(final ByteBuffer buffer)
                {
                    if (!headersWritten)
                    {
                        startResponse(gatewayExchange, journal, journalConfig, requestId);
                        headersWritten = true;
                    }

                    if (captureResBody)
                    {
                        journal.body(ServerDirection.RESPONSE, requestId, buffer);
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

    private boolean shouldCaptureRequestBody(RouteJournalConfig auditDefinition)
    {
        return JournalLevel.FULL == auditDefinition.request();
    }

    private boolean shouldCaptureResponseBody(RouteJournalConfig auditDefinition)
    {
        return JournalLevel.FULL == auditDefinition.response();
    }

    private boolean shouldCaptureRequestHeaders(RouteJournalConfig auditDefinition)
    {
        return JournalLevel.FULL == auditDefinition.request() || JournalLevel.HEADERS == auditDefinition.request();
    }

    private boolean shouldCaptureResponseHeaders(RouteJournalConfig auditDefinition)
    {
        return JournalLevel.FULL == auditDefinition.response() || JournalLevel.HEADERS == auditDefinition.response();
    }
}