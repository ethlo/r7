package com.ethlo.venturi.undertow;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

import com.ethlo.venturi.ShardedJournalWriter;
import com.ethlo.venturi.api.GatewayErrorHandler;
import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.api.GatewayResponse;
import com.ethlo.venturi.api.MutableGatewayAttributes;
import com.ethlo.venturi.api.MutableGatewayResponse;
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
import com.ethlo.venturi.util.GatewayCopier;
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

    private void execute(final HttpServerExchange exchange, final UndertowGatewayRequest incomingRequest, final ExecutableRoute route, final long startNanos)
    {
        final CharSequence requestId = requestIdGenerator.generate();
        final GatewayRequest requestCopy = GatewayCopier.clone(incomingRequest);
        final MutableGatewayResponse upstreamResponse = new UndertowGatewayResponse(exchange);
        final GatewayResponse responseCopy = GatewayCopier.clone(upstreamResponse);
        final MutableGatewayAttributes attrs = new FastGatewayAttributes();
        final GatewayExchange gatewayExchange = new UndertowGatewayExchange(exchange, requestId, requestCopy, incomingRequest, upstreamResponse, responseCopy, attrs, route);

        exchange.putAttachment(GATEWAY_EXCHANGE_KEY, gatewayExchange);
        gatewayExchange.putInternalState(REQUEST_START_NANOS_KEY, startNanos);

        final RouteJournalConfig journalConfig = route.routeDefinition().journal();
        gatewayExchange.putInternalState(ROUTE_JOURNAL_CONFIG_KEY, journalConfig);
        gatewayExchange.attributes().add(ROUTE_ID_KEY, route.id());

        final VlfJournal rawJournal = gatewayExchangeDataWriter.getJournal(requestId);
        final Journal smartJournal = new PolicyJournal(rawJournal, journalConfig, JournalSecurity.DEFAULT_SAFE_HEADERS, gatewayExchange);

        setupJournaling(smartJournal, exchange, gatewayExchange, journalConfig, requestId);

        // For local short-circuits (like Auth errors returning early bodies)
        gatewayExchange.putInternalState(PRE_ROUTING_COMMIT_LISTENER_KEY, (e, body) ->
                {
                    final ByteBuffer resStartLine = StartLineBuilder.buildResponseLine(e);
                    smartJournal.start(ServerDirection.RESPONSE, JournalLevel.NONE, requestId, resStartLine, e.upstreamResponse().headers());
                    smartJournal.body(ServerDirection.RESPONSE, requestId, body);
                }
        );

        handleRoute(route, gatewayExchange);
    }

    private void setupJournaling(final Journal journal, final HttpServerExchange exchange, final GatewayExchange gatewayExchange, final RouteJournalConfig journalConfig, final CharSequence requestId)
    {
        if (journalConfig == null) return;

        final boolean hasRequestLogging = journalConfig.request().level() != JournalLevel.NONE || journalConfig.request().statusOverrides() != null;
        final boolean hasResponseLogging = journalConfig.response().level() != JournalLevel.NONE || journalConfig.response().statusOverrides() != null;

        if (!hasRequestLogging && !hasResponseLogging)
        {
            return; // Only bail if there are NO base levels AND NO overrides
        }

        gatewayExchange.putInternalState(JOURNAL_KEY, journal);

        final AtomicLong requestBytesRead = new AtomicLong(0);
        exchange.addRequestWrapper((factory, ex) -> new ByteCountingStreamSourceConduit(factory.create(), requestBytesRead));
        gatewayExchange.putInternalState(REQUEST_BYTES_READ_KEY, requestBytesRead::get);
        gatewayExchange.putInternalState(RESPONSE_BYTES_SENT_KEY, exchange::getResponseBytesSent);

        // Immediately buffer the request metadata. PolicyJournal will flush it later if needed.
        final ByteBuffer reqStartLine = StartLineBuilder.buildRequestLine(gatewayExchange);
        journal.start(ServerDirection.REQUEST, JournalLevel.NONE, requestId, reqStartLine, gatewayExchange.request().headers());

        // Attach body listeners unconditionally. PolicyJournal will drop the bytes if level != FULL.
        if (journalConfig.request().level() == JournalLevel.FULL)
        {
            exchange.addRequestWrapper((factory, ex) -> new TeeingStreamSourceConduit(factory.create(), buffer -> journal.body(ServerDirection.REQUEST, requestId, buffer)));
        }

        if (journalConfig.response().level() == JournalLevel.FULL)
        {
            exchange.addResponseWrapper((factory, ex) ->
                    new TeeingStreamSinkConduit(factory.create(), buffer -> journal.body(ServerDirection.RESPONSE, requestId, buffer)));
        }

        // Close the transaction when the exchange naturally completes
        exchange.addExchangeCompleteListener((ex, nextListener) ->
        {
            try
            {
                // Push response metadata (PolicyJournal deduplicates if PRE_ROUTING already called this)
                final ByteBuffer resStartLine = StartLineBuilder.buildResponseLine(gatewayExchange);
                journal.start(ServerDirection.RESPONSE, JournalLevel.NONE, requestId, resStartLine, gatewayExchange.upstreamResponse().headers());

                // Seal the file block with the final stats!
                final long duration = System.nanoTime() - gatewayExchange.getInternalState(REQUEST_START_NANOS_KEY);
                journal.end(requestId, gatewayExchange.attributes(), ex.getStatusCode(), exchange.getResponseBytesSent(), requestBytesRead.get(), duration);
            } finally
            {
                nextListener.proceed();
            }
        });
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