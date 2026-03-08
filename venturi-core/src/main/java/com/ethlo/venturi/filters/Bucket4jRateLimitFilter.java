package com.ethlo.venturi.filters;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import com.ethlo.venturi.api.ClientRequestGatewayExchange;
import com.ethlo.venturi.api.ClientRequestGatewayFilter;
import com.ethlo.venturi.spi.GatewayFilterFactory;
import com.ethlo.venturi.util.FastTerminationGatewayResponse;
import com.ethlo.venturi.util.constants.HttpStatuses;
import com.ethlo.venturi.util.constants.MediaTypes;
import com.ethlo.venturi.validation.ValidatableConfig;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;

public final class Bucket4jRateLimitFilter implements GatewayFilterFactory<ClientRequestGatewayFilter, Bucket4jRateLimitFilter.Config>
{
    private static final String FILTER_NAME = "RateLimiter";

    @Override
    public String name()
    {
        return FILTER_NAME;
    }

    @Override
    public Class<Config> configClass()
    {
        return Config.class;
    }

    @Override
    public ClientRequestGatewayFilter create(final Config config)
    {
        return new ClientRequestGatewayFilter()
        {
            private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

            @Override
            public void onClientRequest(final ClientRequestGatewayExchange exchange)
            {
                // Extract IP
                final String clientIp = exchange.clientRequest().remoteAddress().getHostAddress();

                final Bucket bucket = this.buckets.computeIfAbsent(clientIp, this::createNewBucket);
                if (!bucket.tryConsume(1L))
                {
                    exchange.terminate(new FastTerminationGatewayResponse(HttpStatuses.TOO_MANY_REQUESTS, MediaTypes.TEXT_PLAIN, ByteBuffer.wrap("Rate limit exceeded".getBytes(StandardCharsets.UTF_8))));
                }
            }

            private Bucket createNewBucket(final String key)
            {
                final Bandwidth limit = Bandwidth.builder()
                        .capacity(config.capacity())
                        .refillGreedy(config.refillTokens(), config.refillPeriod())
                        .build();

                return Bucket.builder()
                        .addLimit(limit)
                        .build();
            }
        };
    }

    public record Config(long capacity, long refillTokens, Duration refillPeriod) implements ValidatableConfig
    {
    }
}