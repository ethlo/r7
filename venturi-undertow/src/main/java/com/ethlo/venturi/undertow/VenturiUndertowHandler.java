package com.ethlo.venturi.undertow;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

import org.xnio.OptionMap;
import org.xnio.Xnio;
import org.xnio.ssl.XnioSsl;

import com.ethlo.venturi.ShardedJournalWriter;
import com.ethlo.venturi.api.BeforeCommitGatewayFilter;
import com.ethlo.venturi.api.BeforeUpstreamGatewayFilter;
import com.ethlo.venturi.api.FinishedGatewayFilter;
import com.ethlo.venturi.api.GatewayErrorHandler;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.api.GatewayResponse;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.api.InitGatewayFilter;
import com.ethlo.venturi.api.MutableGatewayAttributes;
import com.ethlo.venturi.api.MutableGatewayResponse;
import com.ethlo.venturi.api.StateKey;
import com.ethlo.venturi.config.DefaultGatewayRoute;
import com.ethlo.venturi.config.RouteJournalConfig;
import com.ethlo.venturi.config.RouteRegistry;
import com.ethlo.venturi.core.RequestIdGenerator;
import com.ethlo.venturi.core.SortableRequestIdGenerator;
import com.ethlo.venturi.core.helpers.StartLineBuilder;
import com.ethlo.venturi.journal.PolicyJournal;
import com.ethlo.venturi.journal.api.Journal;
import com.ethlo.venturi.journal.api.JournalLevel;
import com.ethlo.venturi.undertow.config.ServerConfig;
import com.ethlo.venturi.util.FastGatewayAttributes;
import com.ethlo.venturi.util.GatewayCopier;
import com.ethlo.venturi.util.ImmutableGatewayResponse;
import com.ethlo.venturi.util.constants.HttpHeaders;
import com.ethlo.venturi.util.constants.HttpStatuses;
import com.ethlo.venturi.util.constants.MediaTypes;
import com.ethlo.venturi.vlf.VlfJournal;
import io.undertow.protocols.ssl.UndertowXnioSsl;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyClient;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.util.AttachmentKey;
import io.undertow.util.HttpString;

public final class VenturiUndertowHandler implements HttpHandler
{
    public static final AttachmentKey<UndertowGatewayExchange> GATEWAY_EXCHANGE_KEY = AttachmentKey.create(UndertowGatewayExchange.class);
    public static final AttachmentKey<GatewayHeaders> PROXY_RESPONSE_HEADERS = AttachmentKey.create(GatewayHeaders.class);
    static final StateKey<Journal> JOURNAL_KEY = new StateKey<>(".JOURNAL");
    static final StateKey<RouteJournalConfig> ROUTE_JOURNAL_CONFIG_KEY = new StateKey<>(".AUDIT_CONFIG");
    static final StateKey<Long> REQUEST_START_NANOS_KEY = new StateKey<>(".REQUEST_START_NANOS");
    static final StateKey<LongSupplier> REQUEST_BYTES_READ_KEY = new StateKey<>(".REQUEST_BYTES_READ");
    static final StateKey<LongSupplier> RESPONSE_BYTES_SENT_KEY = new StateKey<>(".RESPONSE_BYTES_READ");
    static final StateKey<BiConsumer<UndertowGatewayExchange, ByteBuffer>> PRE_ROUTING_COMMIT_LISTENER_KEY = new StateKey<>(".PRE_ROUTING_COMMIT_LISTENER");
    private static final CharSequence ROUTE_ID_KEY = "route_id";
    private final Map<CharSequence, HttpHandler> routeProxyCache = new ConcurrentHashMap<>();
    private final GatewayErrorHandler errorHandler;
    private final RequestIdGenerator requestIdGenerator = new SortableRequestIdGenerator();
    private final ServerConfig serverConfig;
    private final XnioSsl xnioSsl;
    private final RouteRegistry routeRegistry;
    private final ShardedJournalWriter<VlfJournal> gatewayExchangeDataWriter;

    public VenturiUndertowHandler(final ServerConfig serverConfig, final RouteRegistry routeRegistry, final ShardedJournalWriter<VlfJournal> gatewayExchangeDataWriter, final GatewayErrorHandler errorHandler)
    {
        this.serverConfig = serverConfig;
        this.routeRegistry = routeRegistry;
        this.gatewayExchangeDataWriter = gatewayExchangeDataWriter;
        this.errorHandler = errorHandler;

        this.xnioSsl = getXnioSsl();
    }

    private static UndertowXnioSsl getXnioSsl()
    {
        try
        {
            return new UndertowXnioSsl(Xnio.getInstance(), OptionMap.EMPTY);
        }
        catch (NoSuchProviderException | NoSuchAlgorithmException | KeyManagementException e)
        {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange)
    {
        final long startNanos = System.nanoTime();
        final UndertowGatewayRequest req = new UndertowGatewayRequest(exchange);
        final Optional<GatewayRoute> routeOpt = routeRegistry.findRoute(req);

        if (routeOpt.isEmpty())
        {
            exchange.setStatusCode(HttpStatuses.NOT_FOUND);
            exchange.getRequestHeaders().put(HttpString.tryFromString(HttpHeaders.CONTENT_TYPE), MediaTypes.TEXT_PLAIN);
            exchange.getResponseSender().send(ByteBuffer.wrap("Venturi Server - No route found for request".getBytes(StandardCharsets.UTF_8)));

            return;
        }

        final GatewayRoute route = routeOpt.get();
        execute(exchange, req, route, startNanos);
    }

    private void execute(final HttpServerExchange exchange, final UndertowGatewayRequest incomingRequest, final GatewayRoute route, final long startNanos)
    {
        final CharSequence requestId = requestIdGenerator.generate();
        final GatewayRequest requestCopy = GatewayCopier.clone(incomingRequest);
        final MutableGatewayResponse upstreamResponse = new UndertowGatewayResponse(exchange);
        final GatewayResponse responseCopy = null;
        final MutableGatewayAttributes attrs = new FastGatewayAttributes();
        final UndertowGatewayExchange gatewayExchange = new UndertowGatewayExchange(exchange, requestId, requestCopy, incomingRequest, upstreamResponse, responseCopy, attrs, route);

        exchange.putAttachment(GATEWAY_EXCHANGE_KEY, gatewayExchange);
        gatewayExchange.setAttachment(REQUEST_START_NANOS_KEY, startNanos);

        final RouteJournalConfig journalConfig = ((DefaultGatewayRoute) route).routeDefinition().journal();
        gatewayExchange.setAttachment(ROUTE_JOURNAL_CONFIG_KEY, journalConfig);
        gatewayExchange.attributes().add(ROUTE_ID_KEY, route.id());

        final VlfJournal rawJournal = gatewayExchangeDataWriter.getJournal(requestId);
        final Journal smartJournal = new PolicyJournal(rawJournal, journalConfig, gatewayExchange);

        setupJournaling(smartJournal, exchange, gatewayExchange, journalConfig, requestId);

        // For local short-circuits (like Auth errors returning early bodies)
        gatewayExchange.setAttachment(PRE_ROUTING_COMMIT_LISTENER_KEY, (e, body) ->
                {
                    final ByteBuffer resStartLine = StartLineBuilder.buildResponseLine(e.clientResponse());
                    smartJournal.clientResponse(JournalLevel.NONE, requestId, resStartLine, e.upstreamResponse().headers());
                    smartJournal.responseBody(requestId, body);
                }
        );

        // Make list of filters
        final List<GatewayFilter> filters = new ArrayList<>();
        route.filters().forEach(filters::add);

        // 1. Init filters
        final List<InitGatewayFilter> initFilters = filters.stream().filter(f -> f instanceof InitGatewayFilter).map(InitGatewayFilter.class::cast).toList();
        for (InitGatewayFilter filter : initFilters)
        {
            filter.init(gatewayExchange);
        }

        // 2. Wire the "Finished" lifecycle hook immediately
        final List<FinishedGatewayFilter> finishedGatewayFilters = filters.stream().filter(f -> f instanceof FinishedGatewayFilter).map(FinishedGatewayFilter.class::cast).toList();
        exchange.addExchangeCompleteListener((ex, next) -> {
            try
            {
                for (FinishedGatewayFilter filter : finishedGatewayFilters)
                {
                    filter.finished(gatewayExchange);
                }
            } finally
            {
                next.proceed(); // Essential for Undertow to clean up
            }
        });

        // 4. Run "Before Upstream"
        final List<BeforeUpstreamGatewayFilter> beforeUpstreamGatewayFilters = filters.stream().filter(f -> f instanceof BeforeUpstreamGatewayFilter).map(BeforeUpstreamGatewayFilter.class::cast).toList();
        for (BeforeUpstreamGatewayFilter filter : beforeUpstreamGatewayFilters)
        {
            filter.beforeUpstream(gatewayExchange);
            if (gatewayExchange.clientResponse().isCommitted())
            {
                // Early exit
                return;
            }
        }

        if (gatewayExchange.clientResponse().isCommitted())
        {
            // Filter has determined that it should terminate early
            return;
        }

        // 3. Wire the "Response Headers" hook
        // This triggers just before headers are flushed to the client
        final List<BeforeCommitGatewayFilter> beforeCommitGatewayFilters = filters.stream().filter(f -> f instanceof BeforeCommitGatewayFilter).map(BeforeCommitGatewayFilter.class::cast).toList();
        if (exchange.isResponseChannelAvailable())
        {
            exchange.addResponseCommitListener(ex ->
            {
                // Set a copy here before downstream response filters can taint the headers
                gatewayExchange.setUpstreamResponse(new ImmutableGatewayResponse(new ImmutableHeaderSnapshot(exchange.getResponseHeaders()), exchange.getStatusCode(), true));

                for (BeforeCommitGatewayFilter filter : beforeCommitGatewayFilters)
                {
                    filter.beforeCommit(gatewayExchange);
                }
            });
        }

        proxyCall(route, exchange, gatewayExchange);
    }

    private void proxyCall(GatewayRoute route, final HttpServerExchange exchange, final UndertowGatewayExchange gatewayExchange)
    {
        final ServerConfig.ProxyConfig pConfig = serverConfig.proxy();

        final HttpHandler proxyHandler = routeProxyCache.computeIfAbsent(route.id(), uri ->
                {
                    final LoadBalancingProxyClient rawClient = new LoadBalancingProxyClient()
                            .setConnectionsPerThread(pConfig.connectionsPerThread())
                            .setMaxQueueSize(serverConfig.proxy().maxQueueSize())
                            .setTtl(pConfig.ttl());

                    route.uri().stream()
                            .map(CharSequence::toString)
                            .map(URI::create)
                            .forEach(u -> {
                                if ("https".equalsIgnoreCase(u.getScheme()))
                                {
                                    rawClient.addHost(u, xnioSsl);
                                }
                                else
                                {
                                    rawClient.addHost(u);
                                }
                            });

                    // TODO: Make configurable
                    final boolean logProxyError = true;
                    final ProxyClient client = logProxyError ? new DiagnosticProxyClient(rawClient, errorHandler) : rawClient;

                    return ProxyHandler.builder()
                            .setProxyClient(client)
                            .setMaxRequestTime(pConfig.maxRequestTime())
                            .setReuseXForwarded(false)
                            .setRewriteHostHeader(true)
                            .build();
                }
        );
        try
        {
            proxyHandler.handleRequest(exchange);
        }
        catch (Exception e)
        {
            errorHandler.handleError(gatewayExchange, e);
        }
    }

    private void setupJournaling(final Journal journal, final HttpServerExchange exchange, final UndertowGatewayExchange gatewayExchange, final RouteJournalConfig journalConfig, final CharSequence requestId)
    {
        if (journalConfig == null) return;

        final boolean hasRequestLogging = journalConfig.request().level() != JournalLevel.NONE || journalConfig.request().statusOverrides() != null;
        final boolean hasResponseLogging = journalConfig.response().level() != JournalLevel.NONE || journalConfig.response().statusOverrides() != null;

        if (!hasRequestLogging && !hasResponseLogging)
        {
            return; // Only bail if there are NO base levels AND NO overrides
        }

        gatewayExchange.setAttachment(JOURNAL_KEY, journal);

        final AtomicLong requestBytesRead = new AtomicLong(0);
        exchange.addRequestWrapper((factory, ex) -> new ByteCountingStreamSourceConduit(factory.create(), requestBytesRead));
        gatewayExchange.setAttachment(REQUEST_BYTES_READ_KEY, requestBytesRead::get);
        gatewayExchange.setAttachment(RESPONSE_BYTES_SENT_KEY, exchange::getResponseBytesSent);

        // Immediately copy the raw request metadata and headers
        final ByteBuffer reqStartLine = StartLineBuilder.buildRequestLine(gatewayExchange.clientRequest());
        journal.clientRequest(JournalLevel.NONE, requestId, reqStartLine, gatewayExchange.clientRequest().headers());

        // Attach body listeners if required
        if (journalConfig.request().level() == JournalLevel.FULL)
        {
            exchange.addRequestWrapper((factory, ex) -> new TeeingStreamSourceConduit(factory.create(), buffer -> journal.requestBody(requestId, buffer)));
        }
        if (journalConfig.response().level() == JournalLevel.FULL)
        {
            exchange.addResponseWrapper((factory, ex) ->
                    new TeeingStreamSinkConduit(factory.create(), buffer -> journal.responseBody(requestId, buffer)));
        }

        // Close the transaction when the exchange naturally completes
        exchange.addExchangeCompleteListener((ex, nextListener) ->
        {
            try
            {
                if (gatewayExchange.upstreamResponse() != null)
                {
                    journal.upstreamRequest(journalConfig.request().level(), requestId, StartLineBuilder.buildRequestLine(gatewayExchange.upstreamRequest()), gatewayExchange.upstreamRequest().headers());
                    journal.upstreamResponse(journalConfig.response().level(), requestId, StartLineBuilder.buildResponseLine(gatewayExchange.upstreamResponse()), gatewayExchange.upstreamResponse().headers());
                }

                journal.clientResponse(journalConfig.response().level(), requestId, StartLineBuilder.buildResponseLine(gatewayExchange.clientResponse()), gatewayExchange.clientResponse().headers());

                // Seal the file block with the final stats!
                final long duration = System.nanoTime() - gatewayExchange.getAttachment(REQUEST_START_NANOS_KEY);

                // TODO: Implement CRC!
                final int reqCrc = 0;
                final int resCrc = 0;

                journal.endExchange(requestId, gatewayExchange.attributes(), ex.getStatusCode(), exchange.getResponseBytesSent(), requestBytesRead.get(), duration, reqCrc, resCrc);
            } finally
            {
                nextListener.proceed();
            }
        });
    }
}