package com.ethlo.r7.upstream;

import java.time.Duration;

/**
 * How a route's {@link HttpUpstream} behaves. Built by the server from the route's upstream
 * config and the server config, so this module reads no configuration of its own.
 *
 * @param readTimeout    longest wait for a single read from the upstream
 * @param connectTimeout longest wait for a TCP connection to a target
 * @param idleTtl        how long an idle pooled connection may be reused. Kept below the
 *                       keep-alive timeout of common upstreams (nginx: 75 s) so the gateway
 *                       rarely picks a connection the upstream is closing: a request that fails
 *                       on one can only be retried when it is idempotent
 * @param maxHeadBytes   largest upstream response head (status line, headers, and any trailers)
 * @param maxHeaderCount most header lines in an upstream response head
 */
public record UpstreamOptions(Duration readTimeout, Duration connectTimeout, Duration idleTtl, int maxHeadBytes, int maxHeaderCount)
{
    public static UpstreamOptions defaults()
    {
        return new UpstreamOptions(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(30), 64 * 1024, 200);
    }
}
