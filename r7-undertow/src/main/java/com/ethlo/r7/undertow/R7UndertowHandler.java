package com.ethlo.r7.undertow;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Xnio;

import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.ShardedJournalWriter;
import com.ethlo.r7.api.GatewayErrorHandler;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.config.DefaultGatewayRoute;
import com.ethlo.r7.config.RouteGenerationListener;
import com.ethlo.r7.config.RouteRegistry;
import com.ethlo.r7.config.TimeoutConfig;
import com.ethlo.r7.config.UpstreamConfig;
import com.ethlo.r7.filters.StaticContentFactory;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.server.GatewayPipeline;
import com.ethlo.r7.server.UpstreamConnector;
import com.ethlo.r7.server.UpstreamHandle;
import com.ethlo.r7.server.config.ServerConfig;
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

/**
 * Undertow's side of the gateway: every data-plane request becomes an
 * {@link UndertowGatewayExchange} and goes through the server-neutral {@link GatewayPipeline}.
 * What stays here is what only Undertow can do - build a route's proxy client, serve static
 * files, own the executor blocking filters dispatch to - and the per-request hooks, which live on
 * the exchange.
 */
public final class R7UndertowHandler implements HttpHandler, RouteGenerationListener, UpstreamConnector
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
    static final String UNROUTED_REASON_KEY = GatewayPipeline.UNROUTED_REASON_KEY;
    private static final Logger logger = LoggerFactory.getLogger(R7UndertowHandler.class);
    private static final ConcurrentHashMap<String, CachedStaticHandler> staticHandlers = new ConcurrentHashMap<>();
    private static final String NOSNIFF = "nosniff";

    private final ServerConfig serverConfig;
    private final GatewayErrorHandler errorHandler;
    private final GatewayPipeline pipeline;
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile UndertowXnioSsl xnioSsl;

    public R7UndertowHandler(final ServerConfig serverConfig, final RouteRegistry routeRegistry, final ShardedJournalWriter<R7fJournal> gatewayExchangeDataWriter, final GatewayErrorHandler errorHandler, final GatewayScheduler scheduler)
    {
        this.serverConfig = serverConfig;
        this.errorHandler = errorHandler;

        this.pipeline = new GatewayPipeline(serverConfig, routeRegistry, gatewayExchangeDataWriter, errorHandler, scheduler, this);
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange)
    {
        final UndertowGatewayExchange gatewayExchange = new UndertowGatewayExchange(exchange, this.pipeline, this);
        // Attached before anything can answer: the commit and completion listeners, and
        // DiagnosticProxyClient's error reporting, find the exchange through it.
        exchange.putAttachment(GATEWAY_EXCHANGE_KEY, gatewayExchange);
        this.pipeline.handle(gatewayExchange);
    }

    ExecutorService virtualThreadExecutor()
    {
        return this.virtualThreadExecutor;
    }

    // --- Route generations -------------------------------------------------------------------

    @Override
    public void prepare(final List<GatewayRoute> routes)
    {
        this.pipeline.prepare(routes);
    }

    @Override
    public void retire(final List<GatewayRoute> routes)
    {
        this.pipeline.retire(routes);
        logger.debug("Evicting static handlers");
        staticHandlers.clear();
    }

    /**
     * Requests answered 404 because no route matched, since startup.
     */
    public long unroutedRequests()
    {
        return this.pipeline.unroutedRequests();
    }

    /**
     * Health-checked targets per route id, credentials redacted.
     */
    public Map<String, Map<String, Boolean>> upstreamTargetStates()
    {
        return this.pipeline.upstreamTargetStates();
    }

    // --- Upstreams ---------------------------------------------------------------------------

    /**
     * A route's upstream on Undertow: the proxy handler requests are handed to, and the load
     * balancer behind it, which the health monitor adds targets to and removes them from.
     */
    abstract static class UndertowUpstream implements UpstreamHandle
    {
        private final HttpHandler proxyHandler;

        UndertowUpstream(final HttpHandler proxyHandler)
        {
            this.proxyHandler = proxyHandler;
        }

        HttpHandler proxyHandler()
        {
            return this.proxyHandler;
        }
    }

    @Override
    public UpstreamHandle connect(final DefaultGatewayRoute route)
    {
        final ServerConfig.ProxyConfig pConfig = this.serverConfig.proxy();
        final UpstreamConfig upstream = route.routeDefinition().upstream();

        final LoadBalancingProxyClient rawClient = new LoadBalancingProxyClient(UndertowClient.getInstance(), null, UpstreamHostSelectors.forStrategy(upstream.strategy()))
                .setConnectionsPerThread(pConfig.connectionsPerThread())
                .setMaxQueueSize(pConfig.maxQueueSize())
                .setTtl(Math.toIntExact(pConfig.ttl().toMillis()));

        // Safely extract timeouts, falling back to defaults if not specified in YAML
        final TimeoutConfig timeouts = Optional.ofNullable(upstream.timeouts())
                .orElse(new TimeoutConfig(null));

        final OptionMap clientOptions = OptionMap.builder()
                .set(Options.READ_TIMEOUT, Math.toIntExact(timeouts.read().toMillis()))
                .getMap();

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

        return new UndertowUpstream(handler)
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

    // --- Static content ----------------------------------------------------------------------

    void serveStatic(final HttpServerExchange exchange, final StaticContentFactory.StaticServeRequest staticServeRequest)
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

    private static void sendOwnResponse(final HttpServerExchange exchange, final int status, final String body)
    {
        exchange.setStatusCode(status);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MediaTypes.TEXT_PLAIN_UTF8);
        exchange.getResponseHeaders().put(Headers.X_CONTENT_TYPE_OPTIONS, NOSNIFF);
        exchange.getResponseSender().send(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)));
    }
}
