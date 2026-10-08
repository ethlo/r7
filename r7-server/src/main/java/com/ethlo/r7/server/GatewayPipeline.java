package com.ethlo.r7.server;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.ShardedJournalWriter;
import com.ethlo.r7.UnproxiedUpstreamRequest;
import com.ethlo.r7.UnproxiedUpstreamResponse;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.CompletedGatewayFilter;
import com.ethlo.r7.api.ComponentStatus;
import com.ethlo.r7.api.GatewayErrorHandler;
import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.StateKey;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.config.DefaultGatewayRoute;
import com.ethlo.r7.config.FallbackConfig;
import com.ethlo.r7.config.HealthCheckConfig;
import com.ethlo.r7.config.RouteJournalConfig;
import com.ethlo.r7.config.RouteRegistry;
import com.ethlo.r7.config.UnroutedDefinition;
import com.ethlo.r7.config.UpstreamConfig;
import com.ethlo.r7.core.GatewayContextKeys;
import com.ethlo.r7.core.RequestIdGenerator;
import com.ethlo.r7.core.SortableRequestIdGenerator;
import com.ethlo.r7.core.helpers.StartLineBuilder;
import com.ethlo.r7.filters.StaticContentFactory;
import com.ethlo.r7.journal.HeaderNameSet;
import com.ethlo.r7.journal.JournalSecurity;
import com.ethlo.r7.journal.QueryParameterNameSet;
import com.ethlo.r7.journal.StatefulJournal;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.Journal;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.server.config.ServerConfig;
import com.ethlo.r7.status.PeriodicUpstreamHealthMonitor;
import com.ethlo.r7.status.TrafficMetrics;
import com.ethlo.r7.status.UpstreamHealthMonitor;
import com.ethlo.r7.time.ClockSource;
import com.ethlo.r7.util.CidrRange;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.Fingerprint;
import com.ethlo.r7.util.ImmutableGatewayRequest;
import com.ethlo.r7.util.RegexBudget;
import com.ethlo.r7.util.SensitiveConfig;
import com.ethlo.r7.util.ShortCircuitGatewayResponse;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.util.constants.MediaTypes;

/**
 * The request lifecycle of docs/config.md §3, independent of the HTTP server that carries it:
 * the checks made before any route is consulted, routing, the request filters (dispatching
 * where a filter blocks), fallback routing, the upstream filters and the hand-off to the
 * upstream, response and completed filters on the way out, short-circuits, and what is journaled
 * at each step.
 * <p>
 * One instance per server. It keeps no per-request state: everything about a request lives on its
 * {@link ServerExchange}, and the server calls back into this class through the listeners the
 * exchange registers. Each server hook it calls has exactly one implementation in a running
 * process, so the indirection costs a call the JIT inlines, not an object.
 */
public final class GatewayPipeline
{
    static final String ROUTE_ID_KEY = "gateway.route.id";
    public static final String UNROUTED_REASON_KEY = "gateway.unrouted.reason";
    static final String UPSTREAM_TARGET_KEY = "gateway.target";
    static final String SHORT_CIRCUIT_FILTER_KEY = "gateway.shortcircuit.name";
    private static final String NOSNIFF = "nosniff";
    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String AUTHORIZATION = "Authorization";
    private static final String UPGRADE = "Upgrade";
    private static final int SWITCHING_PROTOCOLS = 101;
    private static final Logger logger = LoggerFactory.getLogger(GatewayPipeline.class);

    /**
     * Built for every route with an upstream before its generation is published, never on a
     * request: a health monitor that only started with a route's first request left a dead
     * target unnoticed until traffic found it. Attached to the route instance rather than kept in
     * a map by route id, so the route table swap publishes both at once, and a request that
     * matched a route before a reload keeps that route's upstream after it.
     */
    private static final StateKey<RouteUpstreamContext> UPSTREAM_CONTEXT = new StateKey<>("upstream-context");

    private final LongAdder unroutedRequests = new LongAdder();
    private final RequestIdGenerator requestIdGenerator = new SortableRequestIdGenerator();
    private final RouteRegistry routeRegistry;
    private final ShardedJournalWriter<? extends Journal> journalWriter;
    private final GatewayErrorHandler errorHandler;
    private final GatewayScheduler scheduler;
    private final UpstreamConnector upstreamConnector;
    private final HeaderNameSet safeRequestHeaders;
    private final HeaderNameSet safeResponseHeaders;
    private final QueryParameterNameSet safeQueryParameters;
    private final Fingerprint fingerprint;
    private final RemoteAddressResolver remoteAddressResolver;

    public GatewayPipeline(final ServerConfig serverConfig, final RouteRegistry routeRegistry, final ShardedJournalWriter<? extends Journal> journalWriter,
                           final GatewayErrorHandler errorHandler, final GatewayScheduler scheduler, final UpstreamConnector upstreamConnector)
    {
        this.routeRegistry = routeRegistry;
        this.journalWriter = journalWriter;
        this.errorHandler = errorHandler;
        this.scheduler = scheduler;
        this.upstreamConnector = upstreamConnector;

        // server.yaml is loaded once at startup (unlike routes.yaml, it is not hot-reloaded),
        // so parsing the configured CIDRs here means every request reuses the same immutable
        // resolver instead of re-parsing it.
        this.remoteAddressResolver = new RemoteAddressResolver(
                serverConfig.limits().trustedProxies().stream().map(CidrRange::parse).toList());

        // The safe-header whitelist is resolved once against the built-in policy here, so every
        // exchange's StatefulJournal reuses the same HeaderNameSet instead of rebuilding it per
        // request.
        final ServerConfig.JournalSecurityConfig journalSecurity = serverConfig.storage().journalSecurity();
        this.safeRequestHeaders = JournalSecurity.resolveSafeRequestHeaders(
                journalSecurity.additionalSafeRequestHeaders(), journalSecurity.safeRequestHeaders());
        this.safeResponseHeaders = JournalSecurity.resolveSafeResponseHeaders(
                journalSecurity.additionalSafeResponseHeaders(), journalSecurity.safeResponseHeaders());
        this.safeQueryParameters = QueryParameterNameSet.of(
                journalSecurity.safeQueryParameters(), !journalSecurity.safeQueryParametersCaseSensitive());
        this.fingerprint = Fingerprint.of(journalSecurity.fingerprintKey());
    }

    // ============================================================================================
    // Request phase
    // ============================================================================================

    /**
     * Entry point for every request on the data plane.
     */
    public void handle(final ServerExchange ex)
    {
        // One routing generation for the whole decision: the route table matched against and the
        // unrouted policy a miss is refused under must come from the same configuration.
        final RouteRegistry.Snapshot routing = this.routeRegistry.snapshot();

        // First of all, framing: a Transfer-Encoding the upstream may parse differently from the
        // server would let the two disagree on where this request's body ends. Checked before
        // anything else can answer, so no other rejection keeps such a connection alive.
        if (!TransferEncodingGuard.isAcceptable(ex.requestHeaders()))
        {
            logger.debug("Rejecting non-canonical Transfer-Encoding: {}", ex.requestHeaders().getAll("Transfer-Encoding"));
            ex.closeConnectionAfterResponse();
            refuseBeforeRouting(ex, routing.unrouted(), HttpStatuses.BAD_REQUEST, ErrorMessages.UNSUPPORTED_TRANSFER_ENCODING.duplicate(), "transfer_encoding");
            return;
        }

        // TRACE echoes the request back, cookies and credentials included, which is what
        // cross-site tracing reads; nothing behind a gateway needs it. 501 rather than 405: no
        // resource supports it, so there is no Allow list to send. Case-insensitive, as the
        // server compares methods: "trace" must not reach an upstream that reads it as TRACE.
        if ("TRACE".equalsIgnoreCase(ex.method()))
        {
            refuseBeforeRouting(ex, routing.unrouted(), HttpStatuses.NOT_IMPLEMENTED, ErrorMessages.TRACE_NOT_SUPPORTED.duplicate(), "trace");
            return;
        }

        // Before any route is consulted: a path the upstream could resolve differently from
        // how the predicates read it would let a request match one route and reach another.
        final RequestPathGuard.Violation pathViolation = RequestPathGuard.check(ex.decodedPath());
        if (pathViolation != null)
        {
            logger.debug("Rejecting ambiguous request path ({}): {}", pathViolation, ex.decodedPath());
            refuseBeforeRouting(ex, routing.unrouted(), HttpStatuses.BAD_REQUEST, ErrorMessages.AMBIGUOUS_PATH.duplicate(), "ambiguous_path");
            return;
        }

        openLiveRequest(ex);
        final DefaultGatewayRoute route;
        try
        {
            route = (DefaultGatewayRoute) routing.findRoute(ex.liveRequest);
        }
        catch (final RegexBudget.RegexBudgetExceededException e)
        {
            // No route was chosen, like the 404 below: journaled only under an unrouted section.
            logger.warn("Route matching refused: {}", e.getMessage());
            refuseBeforeRouting(ex, routing.unrouted(), HttpStatuses.INTERNAL_SERVER_ERROR, ErrorMessages.REGEX_BUDGET_EXCEEDED.duplicate(), "regex_budget");
            return;
        }

        if (route == null)
        {
            // Counted here because no route's SimpleMetrics ever sees these: without it, traffic
            // no route matches - a client on a stale path, a scanner - is invisible on the dashboard.
            this.unroutedRequests.increment();
            refuseBeforeRouting(ex, routing.unrouted(), HttpStatuses.NOT_FOUND, ErrorMessages.NO_ROUTE.duplicate(), "no_route");
            return;
        }

        open(ex, route);
        executeRequestFilters(ex, route, 0);
    }

    private void openLiveRequest(final ServerExchange ex)
    {
        ex.remote = this.remoteAddressResolver.resolve(ex.peerAddress(), ex.requestHeaders());
        ex.liveRequest = ex.openLiveRequest();
    }

    /**
     * Everything a request needs before its filters run: its ID, the snapshot filters and the
     * journal see, the exchange's own state, and the journal entry opened at the route's levels.
     */
    private void open(final ServerExchange ex, final DefaultGatewayRoute route)
    {
        final String requestId = requestIdGenerator.generate();
        ex.requestId = requestId;
        ex.route = route;
        ex.clientRequest = ex.snapshotClientRequest();
        // After the snapshot: filters and the journal keep seeing what the client sent, while
        // the live headers - which are what the proxy copies upstream - lose what must not pass.
        UpstreamHeaderSanitizer.sanitize(ex.requestHeaders(), ex.remote.trustedPeer());
        ex.clientResponse = ex.openClientResponse();
        ex.attributes = new FastGatewayAttributes();
        ex.upstreamRequest = ex.liveRequest;
        ex.upstreamResponse = UnproxiedUpstreamResponse.INSTANCE;
        final RouteJournalConfig journalConfig = route.journal();

        ex.webSocketRequested = "websocket".equalsIgnoreCase(ex.liveRequest.headers().getFirst(UPGRADE));

        final Journal rawJournal = journalWriter.getJournal(requestId);
        final StatefulJournal statefulJournal = new StatefulJournal(rawJournal, journalConfig, ex, safeRequestHeaders, safeResponseHeaders, safeQueryParameters, fingerprint);
        ex.journal = statefulJournal;
        setupJournaling(statefulJournal, ex, journalConfig, requestId);
    }

    /**
     * Answers a request refused before any route was chosen. With an {@code unrouted} section
     * configured, the refusal goes through the same exchange, journal and short-circuit path as
     * a route's, journaled under {@link UnroutedDefinition#ROUTE_ID} with the reason in
     * {@value #UNROUTED_REASON_KEY}; otherwise it is answered directly and leaves no entry.
     * These are what scanners and probes send, so they are worth an audit trail.
     */
    private void refuseBeforeRouting(final ServerExchange ex, final GatewayRoute unroutedRoute, final int status, final ByteBuffer body, final String reason)
    {
        final DefaultGatewayRoute unrouted = (DefaultGatewayRoute) unroutedRoute;
        if (unrouted == null)
        {
            if (ex.clientResponse == null)
            {
                ex.clientResponse = ex.openClientResponse();
            }
            sendOwnResponse(ex, status, body);
            return;
        }
        if (ex.liveRequest == null)
        {
            openLiveRequest(ex);
        }
        open(ex, unrouted);
        ex.attributes().set(UNROUTED_REASON_KEY, reason);
        ex.shortCircuit(new ShortCircuitGatewayResponse(status, MediaTypes.TEXT_PLAIN_UTF8, body));
        shortCircuit(null, ex, unrouted);
    }

    private void executeRequestFilters(final ServerExchange ex, final DefaultGatewayRoute route, final int startIndex)
    {
        final ClientRequestGatewayFilter[] filters = route.clientRequestFilters();

        for (int i = startIndex; i < filters.length; i++)
        {
            final ClientRequestGatewayFilter filter = filters[i];

            // The request has a thread of its own, so a filter that blocks just blocks it.
            try
            {
                filter.onClientRequest(ex);
            }
            catch (final RegexBudget.RegexBudgetExceededException e)
            {
                refuseRegexBudget(ex, e);
            }

            if (ex.isShortCircuited())
            {
                shortCircuit(filter, ex, route);
                return;
            }
        }

        // If we exit the loop natively, all request filters passed. Proceed to proxy.
        continueUpstream(ex, route);
    }

    private void continueUpstream(final ServerExchange ex, final DefaultGatewayRoute route)
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
                ex.attributes().set("gateway.fallback.id", fallbackRoute.id());

                // The fallback route runs as if the request had matched it: its own request
                // filters (dispatching where they need to), then its own upstream filters and
                // upstream, or its own fallback. Global filters already ran once for this request.
                executeRequestFilters(ex, fallbackRoute, fallbackRoute.globalClientRequestFilterCount());
                return;
            }

            registerResponseListeners(ex, route);
            // The route ID is configuration, and naming it tells a client how routes are laid
            // out; it stays in the log and the journal (gateway.route.id), not the body.
            // DEBUG, not INFO: during an outage this runs for every request, and the journal
            // entry already carries the route ID.
            logger.debug("Request {}: no upstream available for route '{}'", ex.requestId(), route.id());
            sendOwnResponse(ex, HttpStatuses.SERVICE_UNAVAILABLE, ErrorMessages.NO_UPSTREAM_AVAILABLE.duplicate());
            return;
        }

        // Before any upstream filter, so whatever a filter sets afterwards is untouched - there is
        // no telling the client's value from a filter's by comparing them. Runs again for a
        // fallback route, harmlessly: by then the client's value is already gone.
        if (Boolean.TRUE.equals(ex.getAttachment(GatewayContextKeys.CLIENT_AUTHORIZATION_CONSUMED)))
        {
            ex.liveRequest.headers().remove(AUTHORIZATION);
        }

        for (final UpstreamRequestGatewayFilter filter : route.beforeUpstreamGatewayFilters())
        {
            try
            {
                filter.onUpstreamRequest(ex);
            }
            catch (final RegexBudget.RegexBudgetExceededException e)
            {
                refuseRegexBudget(ex, e);
            }

            if (ex.isShortCircuited())
            {
                shortCircuit(filter, ex, route);
                return;
            }
        }

        registerResponseListeners(ex, route);

        ex.proxyStartNanos = System.nanoTime();

        // Lifecycle tracking for a genuine upgrade is attached from the response-commit
        // listener, once the upstream's status is known to actually be 101.

        try
        {
            if (upstreamContext == null)
            {
                throw new IllegalStateException("Route '" + route.id() + "' has no upstream and no filter answered the request");
            }
            ex.proxy(upstreamContext.handle());
        }
        catch (final Exception e)
        {
            this.errorHandler.handleError(ex, e);
        }
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

    // ============================================================================================
    // Response phase
    // ============================================================================================

    /**
     * @param route the route whose response and completed filters run for this exchange: the
     *              matched route, or the fallback route it was handed to. Not {@code ex.route},
     *              which stays the matched route - that is what filters see as route() and what
     *              the journal records.
     */
    private static void registerResponseListeners(final ServerExchange ex, final DefaultGatewayRoute route)
    {
        ex.responseRoute = route;
        ex.listenForCommit();
        ex.listenForCompletion();
    }

    /**
     * Called by the server when the response is about to commit.
     */
    public void onResponseCommit(final ServerExchange ex)
    {
        if (ex.wasProxied())
        {
            ex.upstreamResponse = ex.snapshotResponse();
        }

        // Only now, with the upstream's actual status known, can "upgraded" be answered
        // truthfully. Gating on the client's Upgrade header alone (checked before the
        // request was even proxied) would call every rejected upgrade attempt a live
        // websocket, including ones the upstream answered with a plain error.
        final boolean upgraded = ex.clientResponse().status() == SWITCHING_PROTOCOLS && ex.webSocketRequested;
        ex.webSocketUpgraded = upgraded;
        if (upgraded)
        {
            attachWebSocketLifecycleTracking(ex);
        }

        final ClientResponseGatewayFilter[] beforeCommitFilters = ex.responseRoute.beforeCommitGatewayFilters();
        for (int i = beforeCommitFilters.length - 1; i >= 0; i--)
        {
            beforeCommitFilters[i].onClientResponse(ex);
        }
    }

    private static void shortCircuit(final GatewayFilter reasonFilter, final ServerExchange ex, final DefaultGatewayRoute route)
    {
        ex.reasonFilter = reasonFilter;

        // Applied before response filters run - not after, in sendResponse() - so that a filter's
        // onClientResponse (CircuitBreaker's above all: it reads clientResponse().status() to
        // tell a refused half-open probe from a successful one) sees the real refusal outcome
        // instead of the exchange's still-default 200. It also means a filter that sets its own
        // header here is the last word on it, rather than being overwritten by this reapplying
        // the refusal's original headers afterwards.
        final com.ethlo.r7.api.ShortCircuitGatewayResponse terminationResponse = ex.shortCircuitResponse;
        ex.clientResponse().status(terminationResponse.status());
        terminationResponse.headers().forEach(((name, value) -> ex.clientResponse().headers().set(name, value)));

        // Reverse iteration on the way out (onion), exactly like the normal response path in
        // onResponseCommit: the filter declared first is closest to the client and gets the
        // final say, matching a request that actually reached the upstream and came back.
        final ClientResponseGatewayFilter[] beforeCommitFilters = route.beforeCommitGatewayFilters();
        for (int i = beforeCommitFilters.length - 1; i >= 0; i--)
        {
            beforeCommitFilters[i].onClientResponse(ex);
        }

        // Attach the completion listener so the journal records
        ex.responseRoute = route;
        ex.listenForCompletion();

        // The server triggers the listener above when done.
        sendResponse(ex);
    }

    private static void sendResponse(final ServerExchange ex)
    {
        // Check if the core filter requested a native static handoff
        final StaticContentFactory.StaticServeRequest staticServeRequest = ex.getAttachment(StaticContentFactory.STATIC_SERVE_REQUEST_KEY);
        if (staticServeRequest != null)
        {
            ex.serveStatic(staticServeRequest);
            return;
        }

        // The refusal's status and headers were already applied in shortCircuit(), before
        // response filters ran (see the comment there): re-applying terminationResponse here
        // would silently undo whatever a response filter (e.g. AddResponseHeader, CircuitBreaker)
        // just set, since this runs after them.
        // A short-circuit body is r7's (or its configuration's), not the upstream's: r7 vouches
        // for its Content-Type, unless the filter that answered says otherwise.
        final MutableGatewayHeaders headers = ex.clientResponse().headers();
        if (!headers.contains(X_CONTENT_TYPE_OPTIONS))
        {
            headers.set(X_CONTENT_TYPE_OPTIONS, NOSNIFF);
        }
        ex.sendBody(ex.shortCircuitResponse.body());
    }

    /**
     * Answers with a body r7 wrote itself. {@code nosniff} and an explicit charset stop a browser
     * from reading an error that echoes nothing of the request as anything but the plain text it
     * is. Proxied responses are the upstream's to label and are left alone.
     */
    private static void sendOwnResponse(final ServerExchange ex, final int status, final ByteBuffer body)
    {
        ex.clientResponse().status(status);
        ex.clientResponse().headers().set(CONTENT_TYPE, MediaTypes.TEXT_PLAIN_UTF8);
        ex.clientResponse().headers().set(X_CONTENT_TYPE_OPTIONS, NOSNIFF);
        ex.sendBody(body);
    }

    /**
     * A regex that exhausted its budget is a deliberate refusal, not a fault: answered as a 500
     * short-circuit, so response and completion filters run and the journal entry is completed,
     * as for any other refused request. Unexpected exceptions still fail closed without them.
     */
    private static void refuseRegexBudget(final ServerExchange ex, final RegexBudget.RegexBudgetExceededException e)
    {
        logger.warn("Request {} refused: {}", ex.requestId(), e.getMessage());
        ex.shortCircuit(new ShortCircuitGatewayResponse(
                HttpStatuses.INTERNAL_SERVER_ERROR,
                MediaTypes.TEXT_PLAIN_UTF8,
                ErrorMessages.REGEX_BUDGET_EXCEEDED.duplicate()));
    }

    // ============================================================================================
    // Completion
    // ============================================================================================

    /**
     * Called by the server when the exchange completes, before {@link #runCompletedFilters}.
     */
    public void completeJournal(final ServerExchange ex)
    {
        // The journal's own config, not route.journal(): after a fallback the route here is
        // the fallback route, but the journal was opened, and its client request recorded,
        // under the route the request first matched.
        final StatefulJournal journal = ex.journal;
        final RouteJournalConfig journalConfig = journal.routeJournalConfig();
        final String requestId = ex.requestId();

        if (!ex.wasProxied())
        {
            ex.upstreamRequest = UnproxiedUpstreamRequest.INSTANCE;
            ex.upstreamResponse = UnproxiedUpstreamResponse.INSTANCE;
        }

        final String protocol = ex.clientRequest().protocol();
        if (ex.wasProxied())
        {
            // No base passed: StatefulJournal tracks what it actually journaled for the client
            // request and hands that to the delegate, which is the only set a difference is
            // meaningful against — redaction and level downgrades included.
            journal.upstreamRequest(journalConfig.request().level(), requestId, StartLineBuilder.buildRequestLine(ex.upstreamRequest()), ex.upstreamRequest().headers(), null);
            journal.upstreamResponse(journalConfig.response().level(), requestId, ex.upstreamResponse().status(), StartLineBuilder.buildResponseLine(protocol, ex.upstreamResponse()), ex.upstreamResponse().headers());
        }

        journal.clientResponse(journalConfig.response().level(), requestId, ex.clientResponse().status(), StartLineBuilder.buildResponseLine(protocol, ex.clientResponse()), ex.clientResponse().headers(), null);

        // The actual outcome, not the client's ask: a request that asked to upgrade and was
        // refused is an ordinary completed exchange and must be journaled here like any other,
        // not left for the connection-close listener that only a genuine upgrade registers.
        if (ex.isWebsocketUpgraded())
        {
            ex.journalBytes = journal.getBytesWritten();
            return;
        }

        if (journalConfig.isAtLeastMetadata(ex.clientResponse().status()))
        {
            final long requestEndTs = ClockSource.now();
            final long nanoNow = System.nanoTime();
            // Every time but the end is a System.nanoTime() reading, converted against this one
            // moment: a wall-clock step during the exchange cannot reorder them or bend a duration.
            final long requestStartTs = ServerExchange.toEpochNanos(ex.requestStartNanos(), requestEndTs, nanoNow);
            final long proxyStartTs = ServerExchange.toEpochNanos(ex.proxyStartNanos, requestEndTs, nanoNow);
            final long proxyFirstBytesTs = ServerExchange.toEpochNanos(ex.upstreamHeadNanos, requestEndTs, nanoNow);
            final long proxyEndTs = ServerExchange.toEpochNanos(ex.upstreamEndNanos, requestEndTs, nanoNow);

            // Body checksums are supplied by StatefulJournal, which is the only layer
            // that knows which fragments actually reached the journal — it gates them on
            // the effective level. Anything computed here would describe the bytes on the
            // wire instead, and would not match what a reader reads back.
            final BodyChecksum requestBodyChecksum = BodyChecksum.NOT_RECORDED;
            final BodyChecksum responseBodyChecksum = BodyChecksum.NOT_RECORDED;
            final TrafficMetrics trafficMetrics = ex.trafficMetrics();

            tagExchangeAttributes(ex);
            journal.endExchange(requestId, ex.attributes(), requestStartTs, requestEndTs, ex.clientResponse().status(), trafficMetrics.requestHeaderBytes(), trafficMetrics.requestBodyBytes(), trafficMetrics.responseHeaderBytes(), trafficMetrics.responseBodyBytes(), proxyStartTs, proxyFirstBytesTs, proxyEndTs, requestBodyChecksum, responseBodyChecksum);
            ex.journalBytes = journal.getBytesWritten();
        }
    }

    /**
     * Called by the server when the exchange completes, after {@link #completeJournal}.
     */
    public void runCompletedFilters(final ServerExchange ex)
    {
        // Reverse iteration on way out (onion)
        final CompletedGatewayFilter[] completedFilters = ex.responseRoute.completedGatewayFilters();
        for (int i = completedFilters.length - 1; i >= 0; i--)
        {
            completedFilters[i].onCompleted(ex);
        }
    }

    private static void tagExchangeAttributes(final ServerExchange ex)
    {
        ex.attributes().add(ROUTE_ID_KEY, ex.route.id());
        Optional.ofNullable(ex.reasonFilter).map(GatewayFilter::name).ifPresent(name ->
                ex.attributes().add(SHORT_CIRCUIT_FILTER_KEY, name));

        if (ex.wasProxied())
        {
            final String[] attemptedUris = ex.attemptedUpstreams();
            if (attemptedUris.length == 1)
            {
                ex.attributes().set(UPSTREAM_TARGET_KEY, attemptedUris[0]);
            }
            else
            {
                ex.attributes().set(UPSTREAM_TARGET_KEY, List.of(attemptedUris));
            }
        }
    }

    private static void setupJournaling(final Journal journal, final ServerExchange ex, final RouteJournalConfig journalConfig, final String requestId)
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

        final ByteBuffer reqStartLine = StartLineBuilder.buildRequestLine(ex.clientRequest());
        journal.clientRequest(journalConfig.request().level(), requestId, reqStartLine, ex.clientRequest().headers(), ex.clientRequest().remoteAddress(), ((ImmutableGatewayRequest) ex.clientRequest()).ipSource());

        if (ex.webSocketRequested)
        {
            return;
        }

        if (journalConfig.request().level() == JournalLevel.FULL)
        {
            ex.teeRequestBody(buffer -> journal.requestBody(requestId, buffer));
        }
        if (journalConfig.response().level() == JournalLevel.FULL)
        {
            ex.teeResponseBody(buffer -> journal.responseBody(requestId, buffer));
        }
    }

    private static void attachWebSocketLifecycleTracking(final ServerExchange ex)
    {
        final Journal journal = ex.journal;
        final String requestId = ex.requestId();
        ex.onConnectionClose(() -> {
            final long requestEndTs = ClockSource.now();
            final long nanoNow = System.nanoTime();
            final long requestStartTs = ServerExchange.toEpochNanos(ex.requestStartNanos(), requestEndTs, nanoNow);
            final long proxyStartTs = ServerExchange.toEpochNanos(ex.proxyStartNanos, requestEndTs, nanoNow);
            final long proxyFirstBytesTs = ServerExchange.toEpochNanos(ex.upstreamHeadNanos, requestEndTs, nanoNow);
            // The upstream connection became a tunnel; its response has no end of its own.
            final long proxyEndTs = -1;

            // completeJournal returned before tagging an upgraded exchange; its end event is
            // written here, and needs the route and target like any other.
            tagExchangeAttributes(ex);

            final TrafficMetrics trafficMetrics = ex.trafficMetrics();

            journal.endExchange(
                    requestId,
                    ex.attributes(),
                    requestStartTs,
                    requestEndTs,
                    ex.clientResponse().status(),
                    trafficMetrics.requestHeaderBytes(),
                    trafficMetrics.requestBodyBytes(),
                    trafficMetrics.responseHeaderBytes(),
                    trafficMetrics.responseBodyBytes(),
                    proxyStartTs,
                    proxyFirstBytesTs,
                    proxyEndTs,
                    // Websocket exchanges return before the body taps are installed,
                    // so no body is ever journaled for them.
                    BodyChecksum.NOT_RECORDED,
                    BodyChecksum.NOT_RECORDED
            );
        });
    }

    // ============================================================================================
    // Route generations
    // ============================================================================================

    /**
     * Builds the upstream context of every route in a generation before it is published.
     */
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
    }

    private RouteUpstreamContext createUpstreamContext(final DefaultGatewayRoute route)
    {
        final UpstreamHandle handle = this.upstreamConnector.connect(route);

        final Set<URI> targets = route.uri().stream()
                .map(String::toString)
                .map(URI::create)
                .collect(Collectors.toSet());

        final HealthCheckConfig healthCheck = route.routeDefinition().upstream().healthCheck();
        if (healthCheck != null)
        {
            final PeriodicUpstreamHealthMonitor monitor = new PeriodicUpstreamHealthMonitor(targets, handle, healthCheck);
            monitor.start(this.scheduler);
            return new RouteUpstreamContext(handle, monitor);
        }

        // No health monitor is being used, so the static targets are registered with the
        // server's proxy client immediately.
        for (final URI target : targets)
        {
            handle.onTargetUp(target);
        }

        return new RouteUpstreamContext(handle, new UpstreamHealthMonitor()
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
        });
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

    /**
     * The health of a route's upstream targets: a warning while some are out of rotation, an
     * error while all are. Null for a route without an upstream, or whose targets are not
     * health-checked, since then nothing is known about them beyond being configured.
     */
    public ComponentStatus upstreamStatus(final GatewayRoute route)
    {
        final RouteUpstreamContext context = route instanceof DefaultGatewayRoute defaultRoute ? defaultRoute.attachment(UPSTREAM_CONTEXT) : null;
        if (context == null)
        {
            return null;
        }
        final Map<URI, Boolean> states = context.targetStates();
        if (states.isEmpty())
        {
            return null;
        }
        long up = 0;
        for (final boolean inRotation : states.values())
        {
            up += inRotation ? 1 : 0;
        }
        final long down = states.size() - up;
        final Map<String, Long> values = new LinkedHashMap<>();
        values.put("targets_up", up);
        values.put("targets_down", down);
        if (down == 0)
        {
            return new ComponentStatus(ComponentStatus.Health.OK, null, values);
        }
        final String detail = down + " of " + states.size() + (states.size() == 1 ? " target" : " targets") + " down";
        return new ComponentStatus(up == 0 ? ComponentStatus.Health.ERROR : ComponentStatus.Health.WARN, detail, values);
    }

    /**
     * A route's upstream: the server's handle to proxy through, and the monitor that decides
     * whether any of its targets is up.
     */
    record RouteUpstreamContext(UpstreamHandle handle, UpstreamHealthMonitor healthMonitor)
    {
        /**
         * Fast, lock-free check to determine if the route has any healthy upstream nodes.
         * Delegates entirely to the background health monitor's active state.
         */
        boolean hasAvailableTargets()
        {
            return healthMonitor.hasAvailableTargets();
        }

        void stop()
        {
            healthMonitor.stop();
        }

        Map<URI, Boolean> targetStates()
        {
            return healthMonitor.targetStates();
        }
    }
}
