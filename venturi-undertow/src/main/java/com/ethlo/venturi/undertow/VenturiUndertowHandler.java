package com.ethlo.venturi.undertow;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.ethlo.venturi.ShardedJournalWriter;
import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayErrorHandler;
import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.ServerDirection;
import com.ethlo.venturi.api.StateKey;
import com.ethlo.venturi.config.RouteJournalConfig;
import com.ethlo.venturi.config.RouteRegistry;
import com.ethlo.venturi.core.ExecutableRoute;
import com.ethlo.venturi.core.RequestIdGenerator;
import com.ethlo.venturi.core.SortableRequestIdGenerator;
import com.ethlo.venturi.core.helpers.StartLineBuilder;
import com.ethlo.venturi.journal.JournalSecurity;
import com.ethlo.venturi.journal.PolicyJournal;
import com.ethlo.venturi.journal.api.Journal;
import com.ethlo.venturi.journal.api.JournalLevel;
import com.ethlo.venturi.util.FastGatewayAttributes;
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

    private static void sendNotFound(final UndertowGatewayResponse res)
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

    private void execute(final HttpServerExchange exchange, final UndertowGatewayRequest req, final ExecutableRoute route, final long startNanos)
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

        final VlfJournal rawJournal = gatewayExchangeDataWriter.getJournal(requestId);

        // TODO: Consider making safe headers configurable
        final Journal smartJournal = new PolicyJournal(rawJournal, journalConfig, JournalSecurity.DEFAULT_SAFE_HEADERS);

        setupJournaling(smartJournal, exchange, gatewayExchange, journalConfig, requestId);

        gatewayExchange.putInternalState(PRE_ROUTING_COMMIT_LISTENER_KEY, (e, body) ->
                {
                    final ByteBuffer resStartLine = StartLineBuilder.buildResponseLine(e);
                    smartJournal.start(ServerDirection.RESPONSE, journalConfig.response(), requestId, resStartLine, e.response().headers());
                    smartJournal.body(ServerDirection.RESPONSE, requestId, body);
                }
        );

        handleRoute(route, gatewayExchange);
    }

    private void setupJournaling(final Journal journal, final HttpServerExchange exchange, final GatewayExchange gatewayExchange, final RouteJournalConfig journalConfig, final CharSequence requestId)
    {
        if (journalConfig == null || (journalConfig.request() == JournalLevel.NONE && journalConfig.response() == JournalLevel.NONE))
        {
            return;
        }

        gatewayExchange.putInternalState(JOURNAL_KEY, journal);

        final ByteBuffer reqStartLine = StartLineBuilder.buildRequestLine(gatewayExchange);
        journal.start(ServerDirection.REQUEST, journalConfig.request(), requestId, reqStartLine, gatewayExchange.request().headers());

        final AtomicLong requestBytesRead = new AtomicLong(0);
        exchange.addRequestWrapper((factory, ex) -> new ByteCountingStreamSourceConduit(factory.create(), requestBytesRead));
        gatewayExchange.putInternalState(REQUEST_BYTES_READ_KEY, requestBytesRead::get);
        gatewayExchange.putInternalState(RESPONSE_BYTES_SENT_KEY, exchange::getResponseBytesSent);

        if (journalConfig.request() == JournalLevel.FULL)
        {
            gatewayExchange.request().addBodyListener(buffer -> journal.body(ServerDirection.REQUEST, requestId, buffer));
        }

        if (journalConfig.response() == JournalLevel.FULL || journalConfig.response() == JournalLevel.HEADERS)
        {
            gatewayExchange.response().addBodyListener(new Consumer<>()
            {
                private boolean headersWritten = false;

                @Override
                public void accept(final ByteBuffer buffer)
                {
                    if (!headersWritten)
                    {
                        final ByteBuffer resStartLine = StartLineBuilder.buildResponseLine(gatewayExchange);
                        journal.start(ServerDirection.RESPONSE, journalConfig.response(), requestId, resStartLine, gatewayExchange.response().headers());
                        headersWritten = true;
                    }

                    journal.body(ServerDirection.RESPONSE, requestId, buffer);
                }
            });
        }
    }

    private void handleRoute(final ExecutableRoute route, final GatewayExchange gatewayExchange)
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
}