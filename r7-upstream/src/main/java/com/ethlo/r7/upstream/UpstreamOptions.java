package com.ethlo.r7.upstream;

import java.time.Duration;
import java.util.Optional;

import javax.net.ssl.SSLContext;

import com.ethlo.r7.config.TimeoutConfig;
import com.ethlo.r7.config.UpstreamConfig;
import com.ethlo.r7.server.config.ServerConfig;

/**
 * How a route's {@link HttpUpstream} behaves. Built from the route's upstream config and the
 * server config by {@link #of}, the one place that maps configuration onto the client, so that
 * every server reads it the same way.
 *
 * @param readTimeout             longest wait for a single read from the upstream
 * @param connectTimeout          longest wait for a TCP connection (and TLS handshake) to a target
 * @param idleTtl                 how long an idle pooled connection may be reused. Kept below the
 *                                keep-alive timeout of common upstreams (nginx: 75 s) so the gateway
 *                                rarely picks a connection the upstream is closing: a request that
 *                                fails on one can only be retried when it is idempotent
 * @param maxHeadBytes            largest upstream response head (status line, headers, and any trailers)
 * @param maxHeaderCount          most header lines in an upstream response head
 * @param maxConnectionsPerTarget most requests in flight to one target at a time
 * @param maxQueuePerTarget       most requests waiting for one of those; beyond it, 503
 * @param maxRequestTime          longest a whole upstream exchange may take, waiting included
 * @param sslContext              for https targets; null for the JVM's default
 */
public record UpstreamOptions(Duration readTimeout, Duration connectTimeout, Duration idleTtl, int maxHeadBytes, int maxHeaderCount,
                              int maxConnectionsPerTarget, int maxQueuePerTarget, Duration maxRequestTime, SSLContext sslContext)
{
    private static final int MAX_HEAD_BYTES = 64 * 1024;
    private static final int MAX_HEADER_COUNT = 200;

    public static UpstreamOptions defaults()
    {
        return new UpstreamOptions(TimeoutConfig.DEFAULT_READ, TimeoutConfig.DEFAULT_CONNECT, Duration.ofSeconds(30), MAX_HEAD_BYTES, MAX_HEADER_COUNT,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Duration.ofMinutes(1), null);
    }

    /**
     * A route's options: its own timeouts, and the server's proxy limits.
     */
    public static UpstreamOptions of(final ServerConfig serverConfig, final UpstreamConfig upstream)
    {
        final ServerConfig.ProxyConfig proxy = serverConfig.proxy();
        final TimeoutConfig timeouts = Optional.ofNullable(upstream.timeouts()).orElse(TimeoutConfig.defaults());
        return new UpstreamOptions(timeouts.read(), timeouts.connect(), proxy.ttl(), MAX_HEAD_BYTES, MAX_HEADER_COUNT,
                proxy.maxConnectionsPerTarget(), proxy.maxQueueSize(), proxy.maxRequestTime(), null);
    }

    public UpstreamOptions withSslContext(final SSLContext context)
    {
        return new UpstreamOptions(readTimeout, connectTimeout, idleTtl, maxHeadBytes, maxHeaderCount, maxConnectionsPerTarget, maxQueuePerTarget, maxRequestTime, context);
    }

    public UpstreamOptions withLimits(final int maxConnectionsPerTarget, final int maxQueuePerTarget, final Duration maxRequestTime)
    {
        return new UpstreamOptions(readTimeout, connectTimeout, idleTtl, maxHeadBytes, maxHeaderCount, maxConnectionsPerTarget, maxQueuePerTarget, maxRequestTime, sslContext);
    }
}
