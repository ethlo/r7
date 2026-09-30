package com.ethlo.r7.undertow;

import java.net.URI;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Xnio;

import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.ShardedJournalWriter;
import com.ethlo.r7.UnproxiedUpstreamRequest;
import com.ethlo.r7.UnproxiedUpstreamResponse;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.CompletedGatewayFilter;
import com.ethlo.r7.api.GatewayErrorHandler;
import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.api.MutableGatewayAttributes;
import com.ethlo.r7.api.StateKey;
import com.ethlo.r7.api.MutableGatewayResponse;
import com.ethlo.r7.api.ShortCircuitGatewayResponse;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.config.DefaultGatewayRoute;
import com.ethlo.r7.config.FallbackConfig;
import com.ethlo.r7.config.HealthCheckConfig;
import com.ethlo.r7.config.RouteGenerationListener;
import com.ethlo.r7.config.RouteJournalConfig;
import com.ethlo.r7.config.RouteRegistry;
import com.ethlo.r7.config.TimeoutConfig;
import com.ethlo.r7.config.UnroutedDefinition;
import com.ethlo.r7.config.UpstreamConfig;
import com.ethlo.r7.core.GatewayContextKeys;
import com.ethlo.r7.core.RequestIdGenerator;
import com.ethlo.r7.core.SortableRequestIdGenerator;
import com.ethlo.r7.core.helpers.StartLineBuilder;
import com.ethlo.r7.filters.StaticContentFactory;
import com.ethlo.r7.journal.HeaderFingerprint;
import com.ethlo.r7.journal.HeaderNameSet;
import com.ethlo.r7.journal.JournalSecurity;
import com.ethlo.r7.journal.StatefulJournal;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.Journal;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.status.PeriodicUpstreamHealthMonitor;
import com.ethlo.r7.status.TrafficMetricsHandler;
import com.ethlo.r7.status.UpstreamHealthMonitor;
import com.ethlo.r7.status.UpstreamTargetObserver;
import com.ethlo.r7.time.ClockSource;
import com.ethlo.r7.undertow.config.ServerConfig;
import com.ethlo.r7.util.CidrRange;
import com.ethlo.r7.util.RegexBudget;
import com.ethlo.r7.util.SensitiveConfig;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.ImmutableGatewayRequest;
import com.ethlo.r7.util.ImmutableGatewayResponse;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.util.constants.MediaTypes;
import io.undertow.client.UndertowClient;
import io.undertow.protocols.ssl.UndertowXnioSsl;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyClient;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.server.handlers.resource.PathResourceManager;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;

public final class R7UndertowHandler implements HttpHandler, RouteGenerationListener
{
    /**
     * Caches the resource handler for a static content directory alongside the directory's
     * identity (inode/device) at the time it was built, so a delete+recreate, atomic rename, or
     * symlink retarget of the directory can be detected and the handler rebuilt.
     */
    private record CachedStaticHandler(Object directoryIdentity, ResourceHandler handler)
    {
    }

    public static final AttachmentKey<UndertowGatewayExchange> GATEWAY_EXCHANGE_KEY = AttachmentKey.create(UndertowGatewayExchange.class);
    public static final AttachmentKey<Long> PROXY_START_TS_KEY = AttachmentKey.create(Long.class);
    public static final AttachmentKey<Long> PROXY_END_TS_KEY = AttachmentKey.create(Long.class);
    static final AttachmentKey<Boolean> IS_WEBSOCKET_KEY = AttachmentKey.create(Boolean.class);
    /**
     * Whether the upstream actually answered {@code 101 Switching Protocols}, set from the
     * response-commit listener once the status is known. {@link #IS_WEBSOCKET_KEY} only records
     * that the client asked for an upgrade, which an upstream is free to refuse by answering
     * with an ordinary status; treating every such attempt as a live websocket left the request's
     * journal entry uncompleted (it was deferred to a connection-close listener that fires only
     * when the persistent connection eventually closes) and the active-websocket gauge
     * incremented with no matching decrement until then.
     */
    static final AttachmentKey<Boolean> WEBSOCKET_UPGRADED_KEY = AttachmentKey.create(Boolean.class);
    private static final String ROUTE_ID_KEY = "gateway.route.id";
    static final String UNROUTED_REASON_KEY = "gateway.unrouted.reason";
    private static final String UPSTREAM_TARGET_KEY = "gateway.target";
    private static final String SHORT_CIRCUIT_FILTER_KEY = "gateway.shortcircuit.name";
    private static final AttachmentKey<GatewayFilter> REASON_FILTER_KEY = AttachmentKey.create(GatewayFilter.class);
    private static final Logger logger = LoggerFactory.getLogger(R7UndertowHandler.class);
    private static final ConcurrentHashMap<String, CachedStaticHandler> staticHandlers = new ConcurrentHashMap<>();
    /**
     * Built for every route with an upstream before its generation is published, never on a
     * request: a health monitor that only started with a route's first request left a dead
     * target unnoticed until traffic found it. Attached to the route instance rather than kept in
     * a map by route id, so the route table swap publishes both at once, and a request that
     * matched a route before a reload keeps that route's upstream after it.
     */
    private static final StateKey<RouteUpstreamContext> UPSTREAM_CONTEXT = new StateKey<>("upstream-context");
    private final LongAdder unroutedRequests = new LongAdder();
    private final GatewayErrorHandler errorHandler;
    private final RequestIdGenerator requestIdGenerator = new SortableRequestIdGenerator();
    private final ServerConfig serverConfig;
    private final RouteRegistry routeRegistry;
    private final ShardedJournalWriter<R7fJournal> gatewayExchangeDataWriter;
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final GatewayScheduler scheduler;
    private final RemoteAddressResolver remoteAddressResolver;
    private final HeaderNameSet safeRequestHeaders;
    private final HeaderNameSet safeResponseHeaders;
    private final HeaderFingerprint headerFingerprint;
    private static final String NOSNIFF = "nosniff";
    private volatile UndertowXnioSsl xnioSsl;

    public R7UndertowHandler(final ServerConfig serverConfig, final RouteRegistry routeRegistry, final ShardedJournalWriter<R7fJournal> gatewayExchangeDataWriter, final GatewayErrorHandler errorHandler, final GatewayScheduler scheduler)
    {
        this.serverConfig = serverConfig;
        this.routeRegistry = routeRegistry;
        this.gatewayExchangeDataWriter = gatewayExchangeDataWriter;
        this.errorHandler = errorHandler;
        this.scheduler = scheduler;

        // server.yaml is loaded once at startup (unlike routes.yaml, it is not hot-reloaded),
        // so parsing the configured CIDRs here means every request reuses the same immutable
        // resolver instead of re-parsing it.
        this.remoteAddressResolver = new RemoteAddressResolver(
                serverConfig.limits().trustedProxies().stream().map(CidrRange::parse).toList());

        // Same reasoning: the safe-header whitelist is resolved once against the built-in
        // policy here, so every exchange's StatefulJournal reuses the same HeaderNameSet
        // instead of rebuilding it per request.
        final ServerConfig.JournalSecurityConfig journalSecurity = serverConfig.storage().journalSecurity();
        this.safeRequestHeaders = JournalSecurity.resolveSafeRequestHeaders(
                journalSecurity.additionalSafeRequestHeaders(), journalSecurity.safeRequestHeaders());
        this.safeResponseHeaders = JournalSecurity.resolveSafeResponseHeaders(
                journalSecurity.additionalSafeResponseHeaders(), journalSecurity.safeResponseHeaders());
        this.headerFingerprint = HeaderFingerprint.of(journalSecurity.fingerprintKey());
        if (!this.headerFingerprint.isKeyed())
        {
            // Once, at startup: the unkeyed form is kept for compatibility, but it is only as
            // strong as the entropy of the value it hides, and an operator should know that.
            logger.info("Redacted header values are journaled as unkeyed SHA-256 fingerprints; set "
                    + "storage.journal_security.fingerprint_key so that low-entropy secrets cannot be recovered by guessing.");
        }
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

    private static void handleCompleted(final StatefulJournal journal, final HttpServerExchange exchange, final UndertowGatewayExchange gatewayExchange, final RouteJournalConfig journalConfig, final String requestId, final HttpServerExchange serverExchange)
    {
        if (!gatewayExchange.wasProxied())
        {
            gatewayExchange.setUpstreamRequest(UnproxiedUpstreamRequest.INSTANCE);
            gatewayExchange.setUpstreamResponse(UnproxiedUpstreamResponse.INSTANCE);
        }

        if (gatewayExchange.wasProxied())
        {
            // No base passed: StatefulJournal tracks what it actually journaled for the client
            // request and hands that to the delegate, which is the only set a difference is
            // meaningful against — redaction and level downgrades included.
            journal.upstreamRequest(journalConfig.request().level(), requestId, StartLineBuilder.buildRequestLine(gatewayExchange.upstreamRequest()), gatewayExchange.upstreamRequest().headers(), null);
            journal.upstreamResponse(journalConfig.response().level(), requestId, gatewayExchange.upstreamResponse().status(), StartLineBuilder.buildResponseLine(exchange.getProtocol().toString(), gatewayExchange.upstreamResponse()), gatewayExchange.upstreamResponse().headers());
        }

        journal.clientResponse(journalConfig.response().level(), requestId, gatewayExchange.clientResponse().status(), StartLineBuilder.buildResponseLine(exchange.getProtocol().toString(), gatewayExchange.clientResponse()), gatewayExchange.clientResponse().headers(), null);

        // The actual outcome, not the client's ask: a request that asked to upgrade and was
        // refused is an ordinary completed exchange and must be journaled here like any other,
        // not left for the connection-close listener that only a genuine upgrade registers.
        final boolean isWebSocket = gatewayExchange.isWebsocketUpgraded();
        if (isWebSocket)
        {
            final long journalBytes = journal.getBytesWritten();
            gatewayExchange.setJournalBytes(journalBytes);
            return;
        }

        if (journalConfig.isAtLeastMetadata(gatewayExchange.clientResponse().status()))
        {
            final long requestEndTs = ClockSource.now();
            final long requestStartTs = gatewayExchange.getRequestStartEpochNanos();
            final long proxyStartTs = getProxyStartOrMinusOne(exchange);
            final Long tmpProxyEndTs = exchange.getAttachment(PROXY_END_TS_KEY);
            final long proxyEndTs;

            proxyEndTs = Objects.requireNonNullElseGet(tmpProxyEndTs, ClockSource::now);

            final long proxyFirstBytesTs = -1;

            // Body checksums are supplied by StatefulJournal, which is the only layer
            // that knows which fragments actually reached the journal — it gates them on
            // the effective level. Anything computed here would describe the bytes on the
            // wire instead, and would not match what a reader reads back.
            final BodyChecksum requestBodyChecksum = BodyChecksum.NOT_RECORDED;
            final BodyChecksum responseBodyChecksum = BodyChecksum.NOT_RECORDED;
            final TrafficMetricsHandler.TrafficMetrics trafficMetrics = gatewayExchange.getTrafficMetrics();

            tagExchangeAttributes(exchange, gatewayExchange);
            journal.endExchange(requestId, gatewayExchange.attributes(), requestStartTs, requestEndTs, serverExchange.getStatusCode(), trafficMetrics.requestHeaderBytes(), trafficMetrics.requestBodyBytes(), trafficMetrics.responseHeaderBytes(), trafficMetrics.responseBodyBytes(), proxyStartTs, proxyFirstBytesTs, proxyEndTs, requestBodyChecksum, responseBodyChecksum);
            final long journalBytes = journal.getBytesWritten();
            gatewayExchange.setJournalBytes(journalBytes);
        }
    }

    private static void tagExchangeAttributes(HttpServerExchange exchange, UndertowGatewayExchange gatewayExchange)
    {
        gatewayExchange.attributes().add(ROUTE_ID_KEY, gatewayExchange.route().id());
        Optional.ofNullable(exchange.getAttachment(REASON_FILTER_KEY)).map(GatewayFilter::name).ifPresent(name ->
                gatewayExchange.attributes().add(SHORT_CIRCUIT_FILTER_KEY, name));

        if (gatewayExchange.wasProxied())
        {
            final String[] attemptedUris = DiagnosticProxyClient.getAttemptedUris(exchange);
            if (attemptedUris.length == 1)
            {
                gatewayExchange.attributes().set(UPSTREAM_TARGET_KEY, attemptedUris[0]);
            }
            else
            {
                gatewayExchange.attributes().set(UPSTREAM_TARGET_KEY, List.of(attemptedUris));
            }
        }
    }

    private static void sendResponse(HttpServerExchange exchange, UndertowGatewayExchange gatewayExchange)
    {
        // Check if the core filter requested a native static handoff
        final StaticContentFactory.StaticServeRequest staticServeRequest = gatewayExchange.getAttachment(StaticContentFactory.STATIC_SERVE_REQUEST_KEY);

        if (staticServeRequest != null)
        {
            final String staticBasePath = staticServeRequest.baseDirectory().toString();
            try
            {
                final boolean followSymlinks = staticServeRequest.followSymlinks();
                final boolean listDirectory = staticServeRequest.listDirectory();

                // Dotfiles in a web root are usually deployment leftovers - .env, .git/, .htpasswd -
                // and ResourceHandler serves them like any other file. Answered as if absent.
                if (!staticServeRequest.serveHiddenFiles() && StaticContentFactory.StaticServeRequest.isHidden(exchange.getRelativePath()))
                {
                    exchange.setStatusCode(HttpStatuses.NOT_FOUND);
                    exchange.getResponseHeaders().put(Headers.X_CONTENT_TYPE_OPTIONS, NOSNIFF);
                    exchange.endExchange();
                    return;
                }

                final Object directoryIdentity;
                try
                {
                    // fileKey() reflects the underlying inode/device, so it changes whenever the
                    // directory is deleted+recreated, atomically renamed, or reached via a re-pointed
                    // symlink, even though the configured path string stays the same.
                    directoryIdentity = Files.readAttributes(Paths.get(staticBasePath), BasicFileAttributes.class).fileKey();
                }
                catch (final IOException e)
                {
                    // The directory is momentarily missing, e.g. mid atomic swap - fail fast instead
                    // of handing a stale/broken handler a request that may hang.
                    logger.debug("Static content directory '{}' is not currently accessible: {}", staticBasePath, e.getMessage());
                    sendOwnResponse(exchange, HttpStatuses.NOT_FOUND, "Static content directory unavailable");
                    return;
                }

                // Different routes may point at the same base directory with different options,
                // so the options are folded into the cache key alongside the path.
                final String handlerCacheKey = staticBasePath + "|followSymlinks=" + followSymlinks + "|listDirectory=" + listDirectory;

                // Retrieve or (re)build the Undertow ResourceHandler for this directory, discarding
                // any cached handler whose captured identity no longer matches the directory on
                // disk. A null directoryIdentity means the filesystem provider can't supply one
                // (fileKey() is allowed to return null), so never treat it as "unchanged" - always
                // rebuild rather than risk caching a stale handler forever.
                final CachedStaticHandler cached = staticHandlers.compute(handlerCacheKey, (key, existing) ->
                {
                    if (existing != null && directoryIdentity != null && Objects.equals(existing.directoryIdentity(), directoryIdentity))
                    {
                        return existing;
                    }
                    if (existing != null)
                    {
                        logger.debug("Static content directory '{}' was replaced, rebuilding resource handler", staticBasePath);
                    }
                    // With followSymlinks enabled and no safe-path restriction, Undertow follows
                    // any symlink under the base directory unconditionally (PathResourceManager's
                    // "followAll" behaviour). With it disabled, use the plain 2-arg constructor,
                    // which resolves to caseSensitive=true, followLinks=false - passing `false`
                    // as a 3rd positional arg would instead bind to the (base, transferMinSize,
                    // caseSensitive) overload and silently disable case-sensitive matching instead.
                    final PathResourceManager resourceManager = followSymlinks
                            ? new PathResourceManager(Paths.get(staticBasePath), 100, true, new String[0])
                            : new PathResourceManager(Paths.get(staticBasePath), 100);
                    final ResourceHandler handler = new ResourceHandler(resourceManager)
                            .setDirectoryListingEnabled(listDirectory);
                    return new CachedStaticHandler(directoryIdentity, handler);
                });

                // Let Undertow handle the file streaming, MIME types, and zero-copy IO. Set first:
                // the ResourceHandler answers its own 404s and 403s, and a served file's type is
                // guessed from its extension, which a browser must not second-guess by sniffing.
                exchange.getResponseHeaders().put(Headers.X_CONTENT_TYPE_OPTIONS, NOSNIFF);
                cached.handler().handleRequest(exchange);
                return;
            }
            catch (Exception e)
            {
                sendOwnResponse(exchange, HttpStatuses.INTERNAL_SERVER_ERROR, "Error serving static content");
                return;
            }
        }

        // The refusal's status and headers were already applied in shortCircuit(), before
        // response filters ran (see the comment there): re-applying terminationResponse here
        // would silently undo whatever a response filter (e.g. AddResponseHeader, CircuitBreaker)
        // just set, since this runs after them.
        final ShortCircuitGatewayResponse terminationResponse = gatewayExchange.getShortCircuitGatewayResponse();
        // A short-circuit body is r7's (or its configuration's), not the upstream's: r7 vouches
        // for its Content-Type, unless the filter that answered says otherwise.
        if (!exchange.getResponseHeaders().contains(Headers.X_CONTENT_TYPE_OPTIONS))
        {
            exchange.getResponseHeaders().put(Headers.X_CONTENT_TYPE_OPTIONS, NOSNIFF);
        }
        exchange.getResponseSender().send(terminationResponse.body());
    }

    /**
     * Answers with a body r7 wrote itself. {@code nosniff} and an explicit charset stop a browser
     * from reading an error that echoes nothing of the request as anything but the plain text it
     * is. Proxied responses are the upstream's to label and are left alone.
     */
    private static void sendOwnResponse(final HttpServerExchange exchange, final int status, final ByteBuffer body)
    {
        exchange.setStatusCode(status);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MediaTypes.TEXT_PLAIN_UTF8);
        exchange.getResponseHeaders().put(Headers.X_CONTENT_TYPE_OPTIONS, NOSNIFF);
        exchange.getResponseSender().send(body);
    }

    private static void sendOwnResponse(final HttpServerExchange exchange, final int status, final String body)
    {
        sendOwnResponse(exchange, status, ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static void shortCircuit(final GatewayFilter reasonFilter, HttpServerExchange exchange, DefaultGatewayRoute route, UndertowGatewayExchange gatewayExchange, StatefulJournal statefulJournal)
    {
        exchange.putAttachment(REASON_FILTER_KEY, reasonFilter);

        // Applied before response filters run - not after, in sendResponse() - so that a filter's
        // onClientResponse (CircuitBreaker's above all: it reads clientResponse().status() to
        // tell a refused half-open probe from a successful one) sees the real refusal outcome
        // instead of the exchange's still-default 200. It also means a filter that sets its own
        // header here is the last word on it, rather than being overwritten by this reapplying
        // the refusal's original headers afterwards.
        final ShortCircuitGatewayResponse terminationResponse = gatewayExchange.getShortCircuitGatewayResponse();
        gatewayExchange.clientResponse().status(terminationResponse.status());
        terminationResponse.headers().forEach(((name, value) -> gatewayExchange.clientResponse().headers().set(name, value)));

        // Reverse iteration on the way out (onion), exactly like the normal response path in
        // registerResponseListeners: the filter declared first is closest to the client and gets
        // the final say, matching a request that actually reached the upstream and came back.
        final ClientResponseGatewayFilter[] beforeCommitFilters = route.beforeCommitGatewayFilters();
        for (int i = beforeCommitFilters.length - 1; i >= 0; i--)
        {
            beforeCommitFilters[i].onClientResponse(gatewayExchange);
        }

        // Attach the completion listener so the journal records
        setupCompletionHandler(exchange, route, gatewayExchange, statefulJournal);

        // Undertow will trigger the listener above when done.
        sendResponse(exchange, gatewayExchange);
    }

    /**
     * A regex that exhausted its budget is a deliberate refusal, not a fault: answered as a 500
     * short-circuit, so response and completion filters run and the journal entry is completed,
     * as for any other refused request. Unexpected exceptions still fail closed without them.
     */
    private static void refuseRegexBudget(final UndertowGatewayExchange gatewayExchange, final RegexBudget.RegexBudgetExceededException e)
    {
        logger.warn("Request {} refused: {}", gatewayExchange.requestId(), e.getMessage());
        gatewayExchange.shortCircuit(new com.ethlo.r7.util.ShortCircuitGatewayResponse(
                HttpStatuses.INTERNAL_SERVER_ERROR,
                MediaTypes.TEXT_PLAIN_UTF8,
                ErrorMessages.REGEX_BUDGET_EXCEEDED.duplicate()));
    }

    private static void setupCompletionHandler(HttpServerExchange exchange, DefaultGatewayRoute route, UndertowGatewayExchange gatewayExchange, StatefulJournal statefulJournal)
    {
        exchange.addExchangeCompleteListener((serverExchange, next) ->
        {
            // The journal's own config, not route.journal(): after a fallback the route here is
            // the fallback route, but the journal was opened, and its client request recorded,
            // under the route the request first matched.
            handleCompleted(statefulJournal, exchange, gatewayExchange, statefulJournal.routeJournalConfig(), gatewayExchange.requestId(), serverExchange);

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
        UndertowXnioSsl ssl = xnioSsl;
        if (ssl == null)
        {
            synchronized (this)
            {
                ssl = xnioSsl;
                if (ssl == null)
                {
                    try
                    {
                        ssl = new UndertowXnioSsl(Xnio.getInstance(), OptionMap.EMPTY);
                    }
                    catch (NoSuchProviderException | NoSuchAlgorithmException | KeyManagementException e)
                    {
                        throw new IllegalStateException(e);
                    }
                    xnioSsl = ssl;
                }
            }
        }
        return ssl;
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange)
    {
        // One routing generation for the whole decision: the route table matched against and the
        // unrouted policy a miss is refused under must come from the same configuration.
        final RouteRegistry.Snapshot routing = this.routeRegistry.snapshot();

        // First of all, framing: a Transfer-Encoding the upstream may parse differently from
        // Undertow would let the two disagree on where this request's body ends. Checked before
        // anything else can answer, so no other rejection keeps such a connection alive.
        if (!TransferEncodingGuard.isAcceptable(exchange.getRequestHeaders()))
        {
            logger.debug("Rejecting non-canonical Transfer-Encoding: {}", exchange.getRequestHeaders().get(Headers.TRANSFER_ENCODING));
            exchange.setPersistent(false);
            refuseBeforeRouting(exchange, routing.unrouted(), HttpStatuses.BAD_REQUEST, ErrorMessages.UNSUPPORTED_TRANSFER_ENCODING.duplicate(), "transfer_encoding");
            return;
        }

        // TRACE echoes the request back, cookies and credentials included, which is what
        // cross-site tracing reads; nothing behind a gateway needs it. 501 rather than 405: no
        // resource supports it, so there is no Allow list to send.
        if (Methods.TRACE.equals(exchange.getRequestMethod()))
        {
            refuseBeforeRouting(exchange, routing.unrouted(), HttpStatuses.NOT_IMPLEMENTED, ErrorMessages.TRACE_NOT_SUPPORTED.duplicate(), "trace");
            return;
        }

        // Before any route is consulted: a path the upstream could resolve differently from
        // how the predicates read it would let a request match one route and reach another.
        final RequestPathGuard.Violation pathViolation = RequestPathGuard.check(exchange.getRequestPath());
        if (pathViolation != null)
        {
            logger.debug("Rejecting ambiguous request path ({}): {}", pathViolation, exchange.getRequestURI());
            refuseBeforeRouting(exchange, routing.unrouted(), HttpStatuses.BAD_REQUEST, ErrorMessages.AMBIGUOUS_PATH.duplicate(), "ambiguous_path");
            return;
        }

        final RemoteAddressResolver.RemoteInfo remoteInfo = this.remoteAddressResolver.resolve(exchange);
        final UndertowGatewayRequest req = new UndertowGatewayRequest(exchange, remoteInfo.address(), remoteInfo.source());
        final DefaultGatewayRoute route;
        try
        {
            route = (DefaultGatewayRoute) routing.findRoute(req);
        }
        catch (final RegexBudget.RegexBudgetExceededException e)
        {
            // No route was chosen, like the 404 below: journaled only under an unrouted section.
            logger.warn("Route matching refused: {}", e.getMessage());
            refuseBeforeRouting(exchange, routing.unrouted(), HttpStatuses.INTERNAL_SERVER_ERROR, ErrorMessages.REGEX_BUDGET_EXCEEDED.duplicate(), "regex_budget");
            return;
        }

        if (route == null)
        {
            // Counted here because no route's SimpleMetrics ever sees these: without it, traffic
            // no route matches - a client on a stale path, a scanner - is invisible on the dashboard.
            this.unroutedRequests.increment();
            refuseBeforeRouting(exchange, routing.unrouted(), HttpStatuses.NOT_FOUND, ErrorMessages.NO_ROUTE.duplicate(), "no_route");
            return;
        }

        execute(exchange, req, route, remoteInfo.trustedPeer());
    }

    private void execute(final HttpServerExchange exchange, final UndertowGatewayRequest incomingRequest, final DefaultGatewayRoute route, final boolean trustedPeer)
    {
        final OpenedExchange opened = open(exchange, incomingRequest, route, trustedPeer);
        executeRequestFilters(exchange, route, opened.gatewayExchange(), opened.journal(), 0);
    }

    private record OpenedExchange(UndertowGatewayExchange gatewayExchange, StatefulJournal journal)
    {
    }

    /**
     * Everything a request needs before its filters run: its ID, the snapshot filters and the
     * journal see, the gateway exchange, and the journal entry opened at the route's levels.
     */
    private OpenedExchange open(final HttpServerExchange exchange, final UndertowGatewayRequest incomingRequest, final DefaultGatewayRoute route, final boolean trustedPeer)
    {
        final String requestId = requestIdGenerator.generate();
        final GatewayRequest requestCopy = new ImmutableGatewayRequest(exchange.getProtocol().toString(),
                new ImmutableHeaderSnapshot(exchange.getRequestHeaders()),
                exchange.getRequestPath(),
                exchange.getRequestURI(),
                exchange.getRequestMethod().toString(),
                new UndertowQueryParams(exchange.getQueryString(), exchange.getQueryParameters()),
                new UndertowMutableCookies(exchange),
                incomingRequest.remoteAddress(),
                incomingRequest.getRemoteAddressSource()
        );
        // After the snapshot: filters and the journal keep seeing what the client sent, while
        // the live headers - which are what the proxy copies upstream - lose what must not pass.
        UpstreamHeaderSanitizer.sanitize(exchange.getRequestHeaders(), trustedPeer);
        final MutableGatewayResponse clientResponse = new UndertowGatewayResponse(exchange);
        final MutableGatewayAttributes attrs = new FastGatewayAttributes();
        final UndertowGatewayExchange gatewayExchange = new UndertowGatewayExchange(exchange, requestId, requestCopy, incomingRequest, clientResponse, UnproxiedUpstreamResponse.INSTANCE, attrs, route);
        exchange.putAttachment(GATEWAY_EXCHANGE_KEY, gatewayExchange);
        final RouteJournalConfig journalConfig = route.journal();

        final boolean isWebSocket = exchange.getRequestHeaders().contains(io.undertow.util.Headers.UPGRADE) && "websocket".equalsIgnoreCase(exchange.getRequestHeaders().getFirst(io.undertow.util.Headers.UPGRADE));
        exchange.putAttachment(IS_WEBSOCKET_KEY, isWebSocket);

        final R7fJournal rawJournal = gatewayExchangeDataWriter.getJournal(requestId);
        final StatefulJournal statefulJournal = new StatefulJournal(rawJournal, journalConfig, gatewayExchange, safeRequestHeaders, safeResponseHeaders, headerFingerprint);
        setupJournaling(statefulJournal, exchange, gatewayExchange, journalConfig, requestId, isWebSocket);
        return new OpenedExchange(gatewayExchange, statefulJournal);
    }

    /**
     * Answers a request refused before any route was chosen. With an {@code unrouted} section
     * configured, the refusal goes through the same exchange, journal and short-circuit path as
     * a route's, journaled under {@link UnroutedDefinition#ROUTE_ID} with the reason in
     * {@value #UNROUTED_REASON_KEY}; otherwise it is answered directly and leaves no entry.
     * These are what scanners and probes send, so they are worth an audit trail.
     */
    private void refuseBeforeRouting(final HttpServerExchange exchange, final GatewayRoute unroutedRoute, final int status, final ByteBuffer body, final String reason)
    {
        final DefaultGatewayRoute unrouted = (DefaultGatewayRoute) unroutedRoute;
        if (unrouted == null)
        {
            sendOwnResponse(exchange, status, body);
            return;
        }
        final RemoteAddressResolver.RemoteInfo remoteInfo = this.remoteAddressResolver.resolve(exchange);
        final UndertowGatewayRequest request = new UndertowGatewayRequest(exchange, remoteInfo.address(), remoteInfo.source());
        final OpenedExchange opened = open(exchange, request, unrouted, remoteInfo.trustedPeer());
        opened.gatewayExchange().attributes().set(UNROUTED_REASON_KEY, reason);
        opened.gatewayExchange().shortCircuit(new com.ethlo.r7.util.ShortCircuitGatewayResponse(status, MediaTypes.TEXT_PLAIN_UTF8, body));
        shortCircuit(null, exchange, unrouted, opened.gatewayExchange(), opened.journal());
    }

    private void continueUpstream(HttpServerExchange exchange, DefaultGatewayRoute route, UndertowGatewayExchange gatewayExchange, StatefulJournal statefulJournal)
    {
        // Decided before this route's upstream filters run, never after: they shape the request
        // for this route's upstream (InjectBasicAuth, SetRequestHeader, rewrites), and a
        // request already carrying those changes must not be handed to a different upstream.
        // A route without an upstream (static content, canned responses) is finished by its
        // upstream-phase filters short-circuiting, so it has no targets to check.
        // Every published route with an upstream has a context; a missing one would be a route
        // that was never prepared, and it is refused like one whose targets are all down.
        final boolean hasUpstream = route.routeDefinition().upstream() != null;
        final RouteUpstreamContext upstreamContext = hasUpstream ? route.attachment(UPSTREAM_CONTEXT) : null;
        if (hasUpstream && (upstreamContext == null || !upstreamContext.hasAvailableTargets()))
        {
            final DefaultGatewayRoute fallbackRoute = this.fallbackRouteOf(route);
            if (fallbackRoute != null)
            {
                logger.debug("Routing to fallback route: {}", fallbackRoute.id());
                gatewayExchange.attributes().set("gateway.fallback.id", fallbackRoute.id());

                // The fallback route runs as if the request had matched it: its own request
                // filters (dispatching where they need to), then its own upstream filters and
                // upstream, or its own fallback. Global filters already ran once for this request.
                executeRequestFilters(exchange, fallbackRoute, gatewayExchange, statefulJournal, fallbackRoute.globalClientRequestFilterCount());
                return;
            }

            registerResponseListeners(exchange, route, gatewayExchange, statefulJournal);
            // The route ID is configuration, and naming it tells a client how routes are laid
            // out; it stays in the log and the journal (gateway.route.id), not the body.
            // DEBUG, not INFO: during an outage this runs for every request, and the journal
            // entry already carries the route ID.
            logger.debug("Request {}: no upstream available for route '{}'", gatewayExchange.requestId(), route.id());
            sendOwnResponse(exchange, HttpStatuses.SERVICE_UNAVAILABLE, ErrorMessages.NO_UPSTREAM_AVAILABLE.duplicate());
            return;
        }

        // Before any upstream filter, so whatever a filter sets afterwards is untouched - there is
        // no telling the client's value from a filter's by comparing them. Runs again for a
        // fallback route, harmlessly: by then the client's value is already gone.
        if (Boolean.TRUE.equals(gatewayExchange.getAttachment(GatewayContextKeys.CLIENT_AUTHORIZATION_CONSUMED)))
        {
            exchange.getRequestHeaders().remove(Headers.AUTHORIZATION);
        }

        for (final UpstreamRequestGatewayFilter filter : route.beforeUpstreamGatewayFilters())
        {
            try
            {
                filter.onUpstreamRequest(gatewayExchange);
            }
            catch (final RegexBudget.RegexBudgetExceededException e)
            {
                refuseRegexBudget(gatewayExchange, e);
            }

            if (gatewayExchange.isShortCircuited())
            {
                shortCircuit(filter, exchange, route, gatewayExchange, statefulJournal);
                return;
            }
        }

        registerResponseListeners(exchange, route, gatewayExchange, statefulJournal);

        exchange.putAttachment(PROXY_START_TS_KEY, ClockSource.now());

        // Lifecycle tracking for a genuine upgrade is attached from the response-commit
        // listener above, once the upstream's status is known to actually be 101.

        try
        {
            if (upstreamContext == null)
            {
                throw new IllegalStateException("Route '" + route.id() + "' has no upstream and no filter answered the request");
            }
            guardChunkedRequestBody(exchange);
            upstreamContext.getProxyHandler().handleRequest(exchange);
        }
        catch (final Exception e)
        {
            this.errorHandler.handleError(gatewayExchange, e);
        }
    }

    private static void registerResponseListeners(final HttpServerExchange exchange, final DefaultGatewayRoute route, final UndertowGatewayExchange gatewayExchange, final StatefulJournal statefulJournal)
    {
        exchange.addResponseCommitListener(ex ->
        {
            if (gatewayExchange.wasProxied())
            {
                gatewayExchange.setUpstreamResponse(new ImmutableGatewayResponse(exchange.getProtocol().toString(), new ImmutableHeaderSnapshot(exchange.getResponseHeaders()), exchange.getStatusCode(), true));
            }

            // Only now, with the upstream's actual status known, can "upgraded" be answered
            // truthfully. Gating on the client's Upgrade header alone (checked before the
            // request was even proxied) would call every rejected upgrade attempt a live
            // websocket, including ones the upstream answered with a plain error.
            final boolean upgraded = exchange.getStatusCode() == StatusCodes.SWITCHING_PROTOCOLS
                    && Boolean.TRUE.equals(exchange.getAttachment(IS_WEBSOCKET_KEY));
            exchange.putAttachment(WEBSOCKET_UPGRADED_KEY, upgraded);
            if (upgraded)
            {
                attachWebSocketLifecycleTracking(exchange, gatewayExchange, statefulJournal, gatewayExchange.requestId());
            }

            final ClientResponseGatewayFilter[] beforeCommitFilters = route.beforeCommitGatewayFilters();
            for (int i = beforeCommitFilters.length - 1; i >= 0; i--)
            {
                beforeCommitFilters[i].onClientResponse(gatewayExchange);
            }
        });

        setupCompletionHandler(exchange, route, gatewayExchange, statefulJournal);
    }

    @Override
    public void prepare(final List<GatewayRoute> routes)
    {
        try
        {
            for (final GatewayRoute route : routes)
            {
                if (route instanceof DefaultGatewayRoute defaultRoute && defaultRoute.routeDefinition().upstream() != null)
                {
                    defaultRoute.attach(UPSTREAM_CONTEXT, this.createUpstreamContext(defaultRoute));
                }
            }
        }
        catch (final RuntimeException e)
        {
            // Monitors already started for this generation would otherwise probe forever for
            // routes that were never published.
            this.retire(routes);
            throw e;
        }
    }

    /**
     * Stops the health monitors of a replaced or rejected generation. Requests still holding one
     * of its routes finish on its proxy client, which works on without the monitor: only the
     * probing ends, with the targets left as they were last seen.
     */
    @Override
    public void retire(final List<GatewayRoute> routes)
    {
        for (final GatewayRoute route : routes)
        {
            if (route instanceof DefaultGatewayRoute defaultRoute)
            {
                final RouteUpstreamContext context = defaultRoute.attachment(UPSTREAM_CONTEXT);
                if (context != null)
                {
                    context.stop();
                }
            }
        }
        logger.debug("Evicting static handlers");
        staticHandlers.clear();
    }

    private RouteUpstreamContext createUpstreamContext(final DefaultGatewayRoute route)
    {
        final ServerConfig.ProxyConfig pConfig = this.serverConfig.proxy();
        final UpstreamConfig upstream = route.routeDefinition().upstream();

        final LoadBalancingProxyClient rawClient = new LoadBalancingProxyClient(UndertowClient.getInstance(), null, UpstreamHostSelectors.forStrategy(upstream.strategy()))
                .setConnectionsPerThread(pConfig.connectionsPerThread())
                .setMaxQueueSize(pConfig.maxQueueSize())
                .setTtl(Math.toIntExact(pConfig.ttl().toMillis()));

        final Set<URI> targets = route.uri().stream()
                .map(String::toString)
                .map(URI::create)
                .collect(Collectors.toSet());

        // Safely extract timeouts, falling back to defaults if not specified in YAML
        final TimeoutConfig timeouts = Optional.ofNullable(upstream.timeouts())
                .orElse(new TimeoutConfig(null));

        final OptionMap clientOptions = OptionMap.builder()
                .set(Options.READ_TIMEOUT, Math.toIntExact(timeouts.read().toMillis()))
                .getMap();

        final UpstreamTargetObserver undertowAdapter = new UpstreamTargetObserver()
        {
            @Override
            public void onTargetUp(final URI target)
            {
                logger.info("Target {} is reported as available", target);

                // Undertow addHost signature: (URI host, String bindAddress, XnioSsl ssl, OptionMap options)
                if ("https".equalsIgnoreCase(target.getScheme()))
                {
                    rawClient.addHost(target, null, getXnioSsl(), clientOptions);
                }
                else
                {
                    rawClient.addHost(target, null, null, clientOptions);
                }
            }

            @Override
            public void onTargetDown(final URI target)
            {
                logger.info("Target {} is reported as unavailable", target);
                rawClient.removeHost(target);
            }
        };

        final ProxyClient client = new DiagnosticProxyClient(rawClient, this.errorHandler);

        final HttpHandler handler = ProxyHandler.builder()
                .setProxyClient(client)
                .setMaxRequestTime(Math.toIntExact(pConfig.maxRequestTime().toMillis()))
                // Safe only because UpstreamHeaderSanitizer has removed every
                // X-Forwarded-* header an untrusted peer sent: what is left to
                // reuse came from a trusted proxy, whose chain is extended.
                .setReuseXForwarded(true)
                .setRewriteHostHeader(true)
                .build();

        final HealthCheckConfig healthCheck = upstream.healthCheck();

        if (healthCheck != null)
        {
            final PeriodicUpstreamHealthMonitor monitor = new PeriodicUpstreamHealthMonitor(targets, undertowAdapter, healthCheck);
            monitor.start(this.scheduler);
            return new RouteUpstreamContext(handler, monitor);
        }
        else
        {
            // No health monitor is being used, so we must manually register the
            // static targets with the Undertow engine immediately.
            for (final URI target : targets)
            {
                undertowAdapter.onTargetUp(target);
            }

            return new RouteUpstreamContext(handler, new UpstreamHealthMonitor()
            {
                @Override
                public boolean hasAvailableTargets()
                {
                    return true;
                }

                @Override
                public void stop()
                {
                    // NOP
                }
            }
            );
        }
    }

    /**
     * A body without Content-Length is sent to the upstream chunked, and if reading it fails
     * part-way the proxy closes the upstream request off with a terminating chunk, handing the
     * upstream a truncated body as a complete one (see {@link UpstreamAbort}). Such bodies get a
     * guard that aborts the upstream first, and that also enforces any RequestSizeLimit - which
     * a body of undeclared length could otherwise only be held to after the fact. A body with a
     * Content-Length needs neither: the upstream request is fixed-length, so a short body fails
     * rather than completes, and an oversized declared length was refused up front.
     */
    private static void guardChunkedRequestBody(final HttpServerExchange exchange)
    {
        if (exchange.isRequestComplete() || exchange.getRequestHeaders().contains(Headers.CONTENT_LENGTH))
        {
            return;
        }
        final Long limit = exchange.getAttachment(UndertowGatewayExchange.REQUEST_BODY_LIMIT);
        final long maxBytes = limit != null ? limit : Long.MAX_VALUE;
        exchange.addRequestWrapper((factory, ex) -> new RequestBodyGuardConduit(factory.create(), ex, maxBytes));
    }

    /**
     * Requests answered 404 because no route matched, since startup.
     */
    public long unroutedRequests()
    {
        return this.unroutedRequests.sum();
    }

    /**
     * Health-checked targets per route id, credentials redacted. Every route with an upstream is
     * present from the moment routes are loaded; one without a health check has no entries.
     */
    public Map<String, Map<String, Boolean>> upstreamTargetStates()
    {
        final Map<String, Map<String, Boolean>> result = new TreeMap<>();
        for (final GatewayRoute route : this.routeRegistry.getRoutes())
        {
            final RouteUpstreamContext context = route instanceof DefaultGatewayRoute defaultRoute ? defaultRoute.attachment(UPSTREAM_CONTEXT) : null;
            if (context != null)
            {
                final Map<String, Boolean> states = new LinkedHashMap<>();
                context.targetStates().forEach((target, up) -> states.put(SensitiveConfig.redactUrlCredentials(target.toString()), up));
                result.put(route.id(), states);
            }
        }
        return result;
    }

    private DefaultGatewayRoute fallbackRouteOf(final DefaultGatewayRoute route)
    {
        final UpstreamConfig upstream = route.routeDefinition().upstream();
        final FallbackConfig fallbackConfig = upstream != null ? upstream.fallback() : null;
        if (fallbackConfig == null || fallbackConfig.routeId() == null || fallbackConfig.routeId().isBlank())
        {
            return null;
        }
        // Validated to exist at load time; absent only if a reload removed it mid-request, and
        // then the configured baseline answer (503) is the right one.
        return this.routeRegistry.findRoute(fallbackConfig.routeId())
                .map(DefaultGatewayRoute.class::cast)
                .map(route::asFallbackOfThis)
                .orElse(null);
    }

    private void setupJournaling(final Journal journal, final HttpServerExchange exchange, final UndertowGatewayExchange gatewayExchange, final RouteJournalConfig journalConfig, final String requestId, final boolean isWebSocket)
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
            exchange.addRequestWrapper((factory, ex) -> new TeeingStreamSourceConduit(factory.create(), buffer ->
                    journal.requestBody(requestId, buffer)));
        }
        if (journalConfig.response().level() == JournalLevel.FULL)
        {
            exchange.addResponseWrapper((factory, ex) ->
                    new TeeingStreamSinkConduit(factory.create(),
                            buffer -> journal.responseBody(requestId, buffer)));
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
                exchange.dispatch(virtualThreadExecutor, () ->
                        {
                            try
                            {
                                filter.onClientRequest(gatewayExchange);
                            }
                            catch (final RegexBudget.RegexBudgetExceededException e)
                            {
                                refuseRegexBudget(gatewayExchange, e);
                            }

                            if (gatewayExchange.isShortCircuited())
                            {
                                shortCircuit(filter, exchange, route, gatewayExchange, statefulJournal);
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
            try
            {
                filter.onClientRequest(gatewayExchange);
            }
            catch (final RegexBudget.RegexBudgetExceededException e)
            {
                refuseRegexBudget(gatewayExchange, e);
            }

            if (gatewayExchange.isShortCircuited())
            {
                shortCircuit(filter, exchange, route, gatewayExchange, statefulJournal);
                return;
            }
        }

        // If we exit the loop natively, all globalFilters passed. Proceed to proxy.
        continueUpstream(exchange, route, gatewayExchange, statefulJournal);
    }

    private static void attachWebSocketLifecycleTracking(final HttpServerExchange exchange, final UndertowGatewayExchange gatewayExchange, final Journal journal, final String requestId)
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
                    // Websocket exchanges return before the teeing conduits are installed,
                    // so no body is ever journaled for them.
                    BodyChecksum.NOT_RECORDED,
                    BodyChecksum.NOT_RECORDED
            );
        });
    }

    static final class RouteUpstreamContext
    {
        private final HttpHandler proxyHandler;
        private final UpstreamHealthMonitor healthMonitor;

        public RouteUpstreamContext(final HttpHandler proxyHandler, final UpstreamHealthMonitor healthMonitor)
        {
            this.proxyHandler = proxyHandler;
            this.healthMonitor = healthMonitor;
        }

        public HttpHandler getProxyHandler()
        {
            return this.proxyHandler;
        }

        /**
         * Fast, lock-free check to determine if the route has any healthy upstream nodes.
         * Delegates entirely to the background health monitor's active state.
         */
        public boolean hasAvailableTargets()
        {
            return healthMonitor.hasAvailableTargets();
        }

        public void stop()
        {
            healthMonitor.stop();
        }

        public Map<URI, Boolean> targetStates()
        {
            return healthMonitor.targetStates();
        }
    }
}