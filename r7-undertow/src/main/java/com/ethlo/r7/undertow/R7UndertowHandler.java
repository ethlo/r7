package com.ethlo.r7.undertow;

import java.net.URI;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.xnio.OptionMap;
import org.xnio.Xnio;

import com.ethlo.r7.ShardedJournalWriter;
import com.ethlo.r7.UnproxiedUpstreamRequest;
import com.ethlo.r7.UnproxiedUpstreamResponse;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.CompletedGatewayFilter;
import com.ethlo.r7.api.GatewayErrorHandler;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.api.MutableGatewayAttributes;
import com.ethlo.r7.api.MutableGatewayResponse;
import com.ethlo.r7.api.ShortCircuitGatewayResponse;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.config.DefaultGatewayRoute;
import com.ethlo.r7.config.RouteJournalConfig;
import com.ethlo.r7.config.RouteRegistry;
import com.ethlo.r7.core.RequestIdGenerator;
import com.ethlo.r7.core.SortableRequestIdGenerator;
import com.ethlo.r7.core.helpers.StartLineBuilder;
import com.ethlo.r7.journal.StatefulJournal;
import com.ethlo.r7.journal.api.Journal;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.status.TrafficMetricsHandler;
import com.ethlo.r7.time.ClockSource;
import com.ethlo.r7.undertow.config.ServerConfig;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.ImmutableGatewayRequest;
import com.ethlo.r7.util.ImmutableGatewayResponse;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.util.constants.MediaTypes;
import com.ethlo.r7.vlf.VlfJournal;
import io.undertow.protocols.ssl.UndertowXnioSsl;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyClient;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.util.AttachmentKey;
import io.undertow.util.HttpString;

public final class R7UndertowHandler implements HttpHandler
{
    public static final AttachmentKey<UndertowGatewayExchange> GATEWAY_EXCHANGE_KEY = AttachmentKey.create(UndertowGatewayExchange.class);
    public static final AttachmentKey<Long> PROXY_START_TS_KEY = AttachmentKey.create(Long.class);
    public static final AttachmentKey<Long> PROXY_END_TS_KEY = AttachmentKey.create(Long.class);
    static final AttachmentKey<Boolean> IS_WEBSOCKET_KEY = AttachmentKey.create(Boolean.class);
    private static final CharSequence ROUTE_ID_KEY = "route_id";
    private final Map<String, HttpHandler> routeProxyCache = new ConcurrentHashMap<>();
    private final GatewayErrorHandler errorHandler;
    private final RequestIdGenerator requestIdGenerator = new SortableRequestIdGenerator();
    private final ServerConfig serverConfig;
    private final RouteRegistry routeRegistry;
    private final ShardedJournalWriter<VlfJournal> gatewayExchangeDataWriter;
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private UndertowXnioSsl xnioSsl;

    public R7UndertowHandler(final ServerConfig serverConfig, final RouteRegistry routeRegistry, final ShardedJournalWriter<VlfJournal> gatewayExchangeDataWriter, final GatewayErrorHandler errorHandler)
    {
        this.serverConfig = serverConfig;
        this.routeRegistry = routeRegistry;
        this.gatewayExchangeDataWriter = gatewayExchangeDataWriter;
        this.errorHandler = errorHandler;
    }

    private static long getProxyStartOrMinusOne(final HttpServerExchange exchange)
    {
        final Long result = exchange.getAttachment(R7UndertowHandler.PROXY_START_TS_KEY);
        if (result != null)
        {
            return result;
        }
        return -1;
    }

    private static void handleCompleted(final StatefulJournal journal, final HttpServerExchange exchange, final UndertowGatewayExchange gatewayExchange, final RouteJournalConfig journalConfig, final CharSequence requestId, final HttpServerExchange serverExchange)
    {
        if (!gatewayExchange.wasProxied())
        {
            gatewayExchange.setUpstreamRequest(UnproxiedUpstreamRequest.INSTANCE);
            gatewayExchange.setUpstreamResponse(UnproxiedUpstreamResponse.INSTANCE);
        }

        if (gatewayExchange.wasProxied())
        {
            journal.upstreamRequest(journalConfig.request().level(), requestId, StartLineBuilder.buildRequestLine(gatewayExchange.upstreamRequest()), gatewayExchange.upstreamRequest().headers());
            journal.upstreamResponse(journalConfig.response().level(), requestId, gatewayExchange.upstreamResponse().status(), StartLineBuilder.buildResponseLine(gatewayExchange.upstreamResponse()), gatewayExchange.upstreamResponse().headers());
        }

        journal.clientResponse(journalConfig.response().level(), requestId, gatewayExchange.clientResponse().status(), StartLineBuilder.buildResponseLine(gatewayExchange.clientResponse()), gatewayExchange.clientResponse().headers());

        final boolean isWebSocket = Boolean.TRUE.equals(exchange.getAttachment(IS_WEBSOCKET_KEY));
        if (isWebSocket)
        {
            final long journalBytes = journal.getBytesWritten();
            gatewayExchange.setJournalBytes(journalBytes);
            return;
        }

        final long requestEndTs = ClockSource.now();
        final long requestStartTs = gatewayExchange.getRequestStartEpochNanos();
        final long proxyStartTs = getProxyStartOrMinusOne(exchange);
        final Long tmpProxyEndTs = exchange.getAttachment(PROXY_END_TS_KEY);
        final long proxyEndTs;

        proxyEndTs = Objects.requireNonNullElseGet(tmpProxyEndTs, ClockSource::now);

        final long proxyFirstBytesTs = -1;
        final int requestBodyCrc32 = -1;
        final int responseBodyCrc32 = -1;
        final TrafficMetricsHandler.TrafficMetrics trafficMetrics = gatewayExchange.getTrafficMetrics();

        journal.endExchange(requestId, gatewayExchange.attributes(), requestStartTs, requestEndTs, serverExchange.getStatusCode(), trafficMetrics.requestHeaderBytes(), trafficMetrics.requestBodyBytes(), trafficMetrics.responseHeaderBytes(), trafficMetrics.responseBodyBytes(), proxyStartTs, proxyFirstBytesTs, proxyEndTs, requestBodyCrc32, responseBodyCrc32);
        final long journalBytes = journal.getBytesWritten();
        gatewayExchange.setJournalBytes(journalBytes);
    }

    private static void sendResponse(HttpServerExchange exchange, UndertowGatewayExchange gatewayExchange)
    {
        final ShortCircuitGatewayResponse terminationResponse = gatewayExchange.getShortCircuitGatewayResponse();
        gatewayExchange.clientResponse().status(terminationResponse.status());
        terminationResponse.headers().forEach(((name, value) -> gatewayExchange.clientResponse().headers().set(name, value)));
        exchange.getResponseSender().send(terminationResponse.body());
    }

    private static void shortCircuit(HttpServerExchange exchange, DefaultGatewayRoute route, UndertowGatewayExchange gatewayExchange, StatefulJournal statefulJournal)
    {
        for (final ClientResponseGatewayFilter filter : route.beforeCommitGatewayFilters())
        {
            filter.onClientResponse(gatewayExchange);
        }

        // Attach the completion listener so the journal records
        setupCompletionHandler(exchange, route, gatewayExchange, statefulJournal);

        // Undertow will trigger the listener above when done.
        sendResponse(exchange, gatewayExchange);
    }

    private static void setupCompletionHandler(HttpServerExchange exchange, DefaultGatewayRoute route, UndertowGatewayExchange gatewayExchange, StatefulJournal statefulJournal)
    {
        exchange.addExchangeCompleteListener((serverExchange, next) ->
        {
            handleCompleted(statefulJournal, exchange, gatewayExchange, route.routeDefinition().journal(), gatewayExchange.requestId(), serverExchange);

            try
            {
                // Reverse iteration on way out (onion)
                final CompletedGatewayFilter[] completedFilters = route.completedGatewayFilters();
                for (int i = completedFilters.length - 1; i >= 0; i--)
                {
                    completedFilters[i].onCompleted(gatewayExchange);
                }
            } finally
            {
                next.proceed();
            }
        });
    }

    private UndertowXnioSsl getXnioSsl()
    {
        if (xnioSsl == null)
        {
            synchronized (this)
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
        }
        return xnioSsl;
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
        final RouteJournalConfig journalConfig = route.routeDefinition().journal();

        // TODO: Remove me?
        gatewayExchange.attributes().add(ROUTE_ID_KEY, route.id());

        final boolean isWebSocket = exchange.getRequestHeaders().contains(io.undertow.util.Headers.UPGRADE) && "websocket".equalsIgnoreCase(exchange.getRequestHeaders().getFirst(io.undertow.util.Headers.UPGRADE));
        exchange.putAttachment(IS_WEBSOCKET_KEY, isWebSocket);

        final VlfJournal rawJournal = gatewayExchangeDataWriter.getJournal(requestId);
        final StatefulJournal statefulJournal = new StatefulJournal(rawJournal, journalConfig, gatewayExchange);
        setupJournaling(statefulJournal, exchange, gatewayExchange, journalConfig, requestId, isWebSocket);

        executeRequestFilters(exchange, route, gatewayExchange, statefulJournal, 0);
    }

    private void continueUpstream(HttpServerExchange exchange, DefaultGatewayRoute route, UndertowGatewayExchange gatewayExchange, StatefulJournal statefulJournal)
    {
        for (final UpstreamRequestGatewayFilter filter : route.beforeUpstreamGatewayFilters())
        {
            filter.onUpstreamRequest(gatewayExchange);
        }

        exchange.addResponseCommitListener(ex ->
        {
            if (gatewayExchange.wasProxied())
            {
                gatewayExchange.setUpstreamResponse(new ImmutableGatewayResponse(new ImmutableHeaderSnapshot(exchange.getResponseHeaders()), exchange.getStatusCode(), true));
            }

            final ClientResponseGatewayFilter[] beforeCommitFilters = route.beforeCommitGatewayFilters();
            for (int i = beforeCommitFilters.length - 1; i >= 0; i--)
            {
                beforeCommitFilters[i].onClientResponse(gatewayExchange);
            }
        });

        setupCompletionHandler(exchange, route, gatewayExchange, statefulJournal);

        exchange.putAttachment(PROXY_START_TS_KEY, ClockSource.now());

        if (gatewayExchange.isWebsocketUpgraded())
        {
            attachWebSocketLifecycleTracking(exchange, gatewayExchange, statefulJournal, gatewayExchange.requestId());
        }

        proxyCall(route, exchange, gatewayExchange);
    }

    private void proxyCall(final GatewayRoute route, final HttpServerExchange exchange, final UndertowGatewayExchange gatewayExchange)
    {
        final ServerConfig.ProxyConfig pConfig = serverConfig.proxy();

        final HttpHandler proxyHandler = routeProxyCache.computeIfAbsent(route.id().toString(), uri ->
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
                                    rawClient.addHost(u, getXnioSsl());
                                }
                                else
                                {
                                    rawClient.addHost(u);
                                }
                            });

                    final ProxyClient client = new DiagnosticProxyClient(rawClient, errorHandler);

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
        journal.clientRequest(journalConfig.request().level(), requestId, reqStartLine, gatewayExchange.clientRequest().headers(), gatewayExchange.clientRequest().remoteAddress(), ((ImmutableGatewayRequest) gatewayExchange.clientRequest()).ipSource());

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

    private void executeRequestFilters(final HttpServerExchange exchange, final DefaultGatewayRoute route, final UndertowGatewayExchange gatewayExchange, final StatefulJournal statefulJournal, final int startIndex)
    {
        final ClientRequestGatewayFilter[] filters = route.clientRequestFilters();

        for (int i = startIndex; i < filters.length; i++)
        {
            final ClientRequestGatewayFilter filter = filters[i];

            // Only dispatch if the filter needs it AND we are currently on the Undertow IO thread
            if (filter.requiresDispatch() && exchange.isInIoThread())
            {
                final int nextIndex = i + 1;

                // Undertow handles the async hand-off. The IO thread will immediately
                // return after this block and go back to accepting TCP connections.
                exchange.dispatch(this.virtualThreadExecutor, () ->
                        {
                            filter.onClientRequest(gatewayExchange);

                            if (gatewayExchange.isShortCircuited())
                            {
                                shortCircuit(exchange, route, gatewayExchange, statefulJournal);
                            }
                            else
                            {
                                // Resume the loop on the Virtual Thread
                                executeRequestFilters(exchange, route, gatewayExchange, statefulJournal, nextIndex);
                            }
                        }
                );

                return; // Surrender the Undertow IO thread immediately
            }

            // FAST PATH: Execute inline if we don't need to block, OR if we are
            // already running on a Virtual Thread from a previous dispatch.
            filter.onClientRequest(gatewayExchange);

            if (gatewayExchange.isShortCircuited())
            {
                shortCircuit(exchange, route, gatewayExchange, statefulJournal);
                return;
            }
        }

        // If we exit the loop natively, all filters passed. Proceed to proxy.
        continueUpstream(exchange, route, gatewayExchange, statefulJournal);
    }

    private void attachWebSocketLifecycleTracking(final HttpServerExchange exchange, final UndertowGatewayExchange gatewayExchange, final Journal journal, final CharSequence requestId)
    {
        exchange.getConnection().addCloseListener(connection -> {
            final long requestEndTs = ClockSource.now();
            final long requestStartTs = gatewayExchange.getRequestStartEpochNanos();

            final long proxyStartTs = getProxyStartOrMinusOne(exchange);
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