package com.ethlo.venturi.undertow;

import java.net.URI;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.xnio.OptionMap;
import org.xnio.Xnio;
import org.xnio.ssl.XnioSsl;

import com.ethlo.venturi.ShardedJournalWriter;
import com.ethlo.venturi.api.BeforeCommitGatewayFilter;
import com.ethlo.venturi.api.BeforeUpstreamGatewayFilter;
import com.ethlo.venturi.api.ClientRequestGatewayFilter;
import com.ethlo.venturi.api.CompletedGatewayFilter;
import com.ethlo.venturi.api.GatewayErrorHandler;
import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.api.MutableGatewayAttributes;
import com.ethlo.venturi.api.MutableGatewayResponse;
import com.ethlo.venturi.api.TerminationGatewayResponse;
import com.ethlo.venturi.api.UnproxiedUpstreamRequest;
import com.ethlo.venturi.api.UnproxiedUpstreamResponse;
import com.ethlo.venturi.config.DefaultGatewayRoute;
import com.ethlo.venturi.config.RouteJournalConfig;
import com.ethlo.venturi.config.RouteRegistry;
import com.ethlo.venturi.core.RequestIdGenerator;
import com.ethlo.venturi.core.SortableRequestIdGenerator;
import com.ethlo.venturi.core.helpers.StartLineBuilder;
import com.ethlo.venturi.journal.StatefulJournal;
import com.ethlo.venturi.journal.api.Journal;
import com.ethlo.venturi.journal.api.JournalLevel;
import com.ethlo.venturi.status.TrafficMetricsHandler;
import com.ethlo.venturi.time.ClockSource;
import com.ethlo.venturi.undertow.config.ServerConfig;
import com.ethlo.venturi.util.FastGatewayAttributes;
import com.ethlo.venturi.util.ImmutableGatewayRequest;
import com.ethlo.venturi.util.ImmutableGatewayResponse;
import com.ethlo.venturi.util.constants.HttpHeaders;
import com.ethlo.venturi.util.constants.HttpStatuses;
import com.ethlo.venturi.util.constants.MediaTypes;
import com.ethlo.venturi.vlf.VlfJournal;
import io.undertow.protocols.ssl.UndertowXnioSsl;
import io.undertow.server.ExchangeCompletionListener;
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
    public static final AttachmentKey<Long> PROXY_START_TS_KEY = AttachmentKey.create(Long.class);
    public static final AttachmentKey<Long> PROXY_END_TS_KEY = AttachmentKey.create(Long.class);
    static final AttachmentKey<Boolean> IS_WEBSOCKET_KEY = AttachmentKey.create(Boolean.class);
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

    private static long getLongValueOrMinusOne(final HttpServerExchange exchange, final AttachmentKey<Long> key)
    {
        final Long result = exchange.getAttachment(key);
        if (result != null)
        {
            return result;
        }
        return -1;
    }

    private static void handleCompleted(final Journal journal, final HttpServerExchange exchange, final UndertowGatewayExchange gatewayExchange, final RouteJournalConfig journalConfig, final CharSequence requestId, final HttpServerExchange ex, final ExchangeCompletionListener.NextListener nextListener)
    {
        if (!gatewayExchange.wasProxied())
        {
            gatewayExchange.setUpstreamRequest(UnproxiedUpstreamRequest.INSTANCE);
            gatewayExchange.setUpstreamResponse(UnproxiedUpstreamResponse.INSTANCE);
        }
        final long journalBytes = ((StatefulJournal) journal).getBytesWritten();
        gatewayExchange.setJournalBytes(journalBytes);

        try
        {
            if (gatewayExchange.wasProxied())
            {
                journal.upstreamRequest(journalConfig.request().level(), requestId, StartLineBuilder.buildRequestLine(gatewayExchange.upstreamRequest()), gatewayExchange.upstreamRequest().headers());
                journal.upstreamResponse(journalConfig.response().level(), requestId, gatewayExchange.upstreamResponse().status(), StartLineBuilder.buildResponseLine(gatewayExchange.upstreamResponse()), gatewayExchange.upstreamResponse().headers());
            }

            journal.clientResponse(journalConfig.response().level(), requestId, gatewayExchange.clientResponse().status(), StartLineBuilder.buildResponseLine(gatewayExchange.clientResponse()), gatewayExchange.clientResponse().headers());

            final boolean isWebSocket = Boolean.TRUE.equals(exchange.getAttachment(IS_WEBSOCKET_KEY));
            if (isWebSocket)
            {
                return;
            }

            final long requestEndTs = ClockSource.now();
            final long requestStartTs = gatewayExchange.getRequestStartEpochNanos();
            final long proxyStartTs = getLongValueOrMinusOne(exchange, PROXY_START_TS_KEY);
            final Long tmpProxyEndTs = exchange.getAttachment(PROXY_END_TS_KEY);
            final long proxyEndTs;

            proxyEndTs = Objects.requireNonNullElseGet(tmpProxyEndTs, ClockSource::now);

            final long proxyFirstBytesTs = -1;
            final int requestBodyCrc32 = -1;
            final int responseBodyCrc32 = -1;
            final TrafficMetricsHandler.TrafficMetrics trafficMetrics = gatewayExchange.getTrafficMetrics();

            journal.endExchange(requestId, gatewayExchange.attributes(), requestStartTs, requestEndTs, ex.getStatusCode(), trafficMetrics.requestHeaderBytes(), trafficMetrics.requestBodyBytes(), trafficMetrics.responseHeaderBytes(), trafficMetrics.responseBodyBytes(), proxyStartTs, proxyFirstBytesTs, proxyEndTs, requestBodyCrc32, responseBodyCrc32);
        } finally
        {
            nextListener.proceed();
        }
    }

    private static void sendTermination(HttpServerExchange exchange, UndertowGatewayExchange gatewayExchange)
    {
        final TerminationGatewayResponse terminationResponse = gatewayExchange.getTerminated();
        gatewayExchange.clientResponse().status(terminationResponse.status());
        terminationResponse.headers().forEach(((name, value) -> gatewayExchange.clientResponse().headers().set(name, value)));
        exchange.getResponseSender().send(terminationResponse.body());
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange)
    {
        final UndertowGatewayRequest req = new UndertowGatewayRequest(exchange);
        final DefaultGatewayRoute route = (DefaultGatewayRoute) routeRegistry.findRoute(req);

        if (route == null)
        {
            exchange.setStatusCode(HttpStatuses.NOT_FOUND);
            exchange.getRequestHeaders().put(HttpString.tryFromString(HttpHeaders.CONTENT_TYPE), MediaTypes.TEXT_PLAIN);
            exchange.getResponseSender().send(ErrorMessages.NO_ROUTE.duplicate());
            return;
        }

        execute(exchange, req, route);
    }

    private void execute(final HttpServerExchange exchange, final UndertowGatewayRequest incomingRequest, final DefaultGatewayRoute route)
    {
        final CharSequence requestId = requestIdGenerator.generate();
        final GatewayRequest requestCopy = new ImmutableGatewayRequest(new ImmutableHeaderSnapshot(exchange.getRequestHeaders()), exchange.getRequestPath(), exchange.getRequestURI(), exchange.getRequestMethod().toString(), exchange.getDecodedQueryString(), incomingRequest.remoteAddress(), incomingRequest.getRemoteAddressSource());
        final MutableGatewayResponse clientResponse = new UndertowGatewayResponse(exchange);
        final MutableGatewayAttributes attrs = new FastGatewayAttributes();
        final UndertowGatewayExchange gatewayExchange = new UndertowGatewayExchange(exchange, requestId, requestCopy, incomingRequest, clientResponse, UnproxiedUpstreamResponse.INSTANCE, attrs, route);
        exchange.putAttachment(GATEWAY_EXCHANGE_KEY, gatewayExchange);
        final RouteJournalConfig journalConfig = ((DefaultGatewayRoute) route).routeDefinition().journal();

        // TODO: Remove me?
        gatewayExchange.attributes().add(ROUTE_ID_KEY, route.id());

        final boolean isWebSocket = exchange.getRequestHeaders().contains(io.undertow.util.Headers.UPGRADE) && "websocket".equalsIgnoreCase(exchange.getRequestHeaders().getFirst(io.undertow.util.Headers.UPGRADE));
        exchange.putAttachment(IS_WEBSOCKET_KEY, isWebSocket);

        final VlfJournal rawJournal = gatewayExchangeDataWriter.getJournal(requestId);
        final Journal statefulJournal = new StatefulJournal(rawJournal, journalConfig, gatewayExchange);
        setupJournaling(statefulJournal, exchange, gatewayExchange, journalConfig, requestId, isWebSocket);

        for (final ClientRequestGatewayFilter filter : route.clientRequestFilters())
        {
            filter.onClientRequest(gatewayExchange);
            if (gatewayExchange.getTerminated() != null)
            {
                // Early exit
                break;
            }
        }


        exchange.addExchangeCompleteListener((ex, next) ->
        {
            handleCompleted(statefulJournal, exchange, gatewayExchange, journalConfig, requestId, ex, next);

            try
            {
                for (final CompletedGatewayFilter filter : route.completedGatewayFilters())
                {
                    filter.onCompleted(gatewayExchange);
                }
            } finally
            {
                next.proceed();
            }
        });

        if (exchange.isResponseChannelAvailable())
        {
            exchange.addResponseCommitListener(ex ->
            {
                if (gatewayExchange.wasProxied())
                {
                    gatewayExchange.setUpstreamResponse(new ImmutableGatewayResponse(new ImmutableHeaderSnapshot(exchange.getResponseHeaders()), exchange.getStatusCode(), true));
                }

                for (final BeforeCommitGatewayFilter filter : route.beforeCommitGatewayFilters())
                {
                    filter.onClientResponse(gatewayExchange);
                }
            });
        }

        if (gatewayExchange.getTerminated() != null)
        {
            sendTermination(exchange, gatewayExchange);
        }
        else
        {
            for (final BeforeUpstreamGatewayFilter filter : route.beforeUpstreamGatewayFilters())
            {
                filter.onUpstreamRequest(gatewayExchange);
            }

            exchange.putAttachment(PROXY_START_TS_KEY, ClockSource.now());

            if (isWebSocket)
            {
                attachWebSocketLifecycleTracking(exchange, gatewayExchange, statefulJournal, requestId);
            }

            proxyCall(route, exchange, gatewayExchange);
        }
    }

    private void proxyCall(final GatewayRoute route, final HttpServerExchange exchange, final UndertowGatewayExchange gatewayExchange)
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
        catch (final Exception e)
        {
            errorHandler.handleError(gatewayExchange, e);
        }
    }

    private void setupJournaling(final Journal journal, final HttpServerExchange exchange, final UndertowGatewayExchange gatewayExchange, final RouteJournalConfig journalConfig, final CharSequence requestId, final boolean isWebSocket)
    {
        if (journalConfig == null)
        {
            return;
        }

        final boolean hasRequestLogging = journalConfig.request().level() != JournalLevel.NONE || journalConfig.request().statusOverrides() != null;
        final boolean hasResponseLogging = journalConfig.response().level() != JournalLevel.NONE || journalConfig.response().statusOverrides() != null;

        if (!hasRequestLogging && !hasResponseLogging)
        {
            return;
        }

        final ByteBuffer reqStartLine = StartLineBuilder.buildRequestLine(gatewayExchange.clientRequest());
        journal.clientRequest(JournalLevel.NONE, requestId, reqStartLine, gatewayExchange.clientRequest().headers(), gatewayExchange.clientRequest().remoteAddress(), ((ImmutableGatewayRequest) gatewayExchange.clientRequest()).ipSource());

        if (isWebSocket)
        {
            return;
        }

        if (journalConfig.request().level() == JournalLevel.FULL)
        {
            exchange.addRequestWrapper((factory, ex) -> new TeeingStreamSourceConduit(factory.create(), buffer -> journal.requestBody(requestId, buffer)));
        }
        if (journalConfig.response().level() == JournalLevel.FULL)
        {
            exchange.addResponseWrapper((factory, ex) ->
                    new TeeingStreamSinkConduit(factory.create(), buffer ->
                    {
                        journal.responseBody(requestId, buffer);
                    }
                    ));
        }
    }

    private void attachWebSocketLifecycleTracking(final HttpServerExchange exchange, final UndertowGatewayExchange gatewayExchange, final Journal journal, final CharSequence requestId)
    {
        exchange.getConnection().addCloseListener(connection -> {
            final long requestEndTs = ClockSource.now();
            final long requestStartTs = gatewayExchange.getRequestStartEpochNanos();

            final long proxyStartTs = getLongValueOrMinusOne(exchange, PROXY_START_TS_KEY);
            final Long tmpProxyEndTs = exchange.getAttachment(PROXY_END_TS_KEY);
            final long proxyEndTs = Objects.requireNonNullElse(tmpProxyEndTs, requestEndTs);

            final TrafficMetricsHandler.TrafficMetrics trafficMetrics = gatewayExchange.getTrafficMetrics();

            journal.endExchange(
                    requestId,
                    gatewayExchange.attributes(),
                    requestStartTs,
                    requestEndTs,
                    exchange.getStatusCode(),
                    trafficMetrics.requestHeaderBytes(),
                    trafficMetrics.requestBodyBytes(),
                    trafficMetrics.responseHeaderBytes(),
                    trafficMetrics.responseBodyBytes(),
                    proxyStartTs,
                    -1,
                    proxyEndTs,
                    -1,
                    -1
            );
        });
    }
}