package com.ethlo.venturi.filters;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

import com.ethlo.venturi.api.BeforeCommitGatewayFilter;
import com.ethlo.venturi.api.ClientRequestGatewayExchange;
import com.ethlo.venturi.api.ClientRequestGatewayFilter;
import com.ethlo.venturi.api.ClientResponseGatewayExchange;
import com.ethlo.venturi.api.MutableGatewayHeaders;
import com.ethlo.venturi.api.StateKey;
import com.ethlo.venturi.core.GatewayContextKeys;
import com.ethlo.venturi.core.ShortInfo;
import com.ethlo.venturi.spi.GatewayFilterFactory;
import com.ethlo.venturi.util.FastTerminationGatewayResponse;
import com.ethlo.venturi.util.MutableFastGatewayHeaders;
import com.ethlo.venturi.util.ValidatorUtils;
import com.ethlo.venturi.util.constants.HttpHeaders;
import com.ethlo.venturi.util.constants.HttpStatuses;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

public final class Bucket4jRateLimitFilter implements GatewayFilterFactory<Bucket4jRateLimitFilter.Config>
{
    private static final byte[] REJECT_PAYLOAD = "Rate limit exceeded".getBytes(StandardCharsets.UTF_8);
    private static final String FILTER_NAME = "RateLimiter";
    private static final StateKey<Long> REMAINING_TOKENS_KEY = new StateKey<>("remaining_tokens");

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
        return new GF(config);
    }

    public record Config(Long capacity, Long refillTokens, Duration refillPeriod, Long maxBuckets,
                         Duration maxBucketTTL) implements ValidatableConfig
    {
        private static final long DEFAULT_MAX_BUCKETS = 10_000L;
        private static final long MINIMUM_EXPIRY_TIME_MILLIS = Duration.ofSeconds(30).toMillis();

        @Override
        public Duration maxBucketTTL()
        {
            return Optional.ofNullable(maxBucketTTL).orElse(Duration.ofMillis(Math.max(refillPeriod.toMillis() * 10, MINIMUM_EXPIRY_TIME_MILLIS)));
        }

        @Override
        public Long maxBuckets()
        {
            return Optional.ofNullable(maxBuckets).orElse(DEFAULT_MAX_BUCKETS);
        }

        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result)
                    .required(FILTER_NAME, "capacity", capacity)
                    .required(FILTER_NAME, "refill_tokens", refillTokens)
                    .required(FILTER_NAME, "refill_period", refillPeriod);
        }

        @Override
        public String toString()
        {
            return new StringJoiner(", ", Config.class.getSimpleName() + "[", "]")
                    .add("capacity=" + capacity())
                    .add("refillTokens=" + refillTokens())
                    .add("refillPeriod=" + refillPeriod())
                    .add("maxBuckets=" + maxBuckets())
                    .add("maxBucketTTL=" + maxBucketTTL())
                    .toString();
        }
    }

    private static class GF implements ClientRequestGatewayFilter, BeforeCommitGatewayFilter, ShortInfo
    {
        private final Cache<String, Bucket> buckets;
        private final Config config;
        private final String capacityString;

        public GF(Config config)
        {
            this.config = config;
            this.capacityString = Long.toString(config.capacity());
            buckets = Caffeine.newBuilder()
                    .maximumSize(config.maxBuckets())
                    .expireAfterAccess(config.maxBucketTTL())
                    .build();
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            // Check if a prior filter declared a specific rate-limit identity
            final String customKey = exchange.getAttachment(GatewayContextKeys.RATE_LIMIT_KEY);

            // Fallback to physical IP address if no identity was provided
            final String key = (customKey != null) ? customKey : exchange.clientRequest().remoteAddress().getHostAddress();

            // Find and set bucket
            final Bucket bucket = buckets.get(key, this::createNewBucket);
            final ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1L);
            exchange.setAttachment(REMAINING_TOKENS_KEY, probe.getRemainingTokens());

            if (!probe.isConsumed())
            {
                // Calculate how long the client needs to wait for at least 1 token
                final long waitNanos = probe.getNanosToWaitForRefill();
                final long waitSeconds = TimeUnit.NANOSECONDS.toSeconds(waitNanos) + 1L;

                // Standard rate limiting headers
                final MutableGatewayHeaders headers = new MutableFastGatewayHeaders(3)
                        .set(HttpHeaders.RETRY_AFTER, String.valueOf(waitSeconds))
                        .set(HttpHeaders.X_RATELIMIT_LIMIT, capacityString)
                        .set(HttpHeaders.X_RATELIMIT_REMAINING, "0");

                exchange.terminate(new FastTerminationGatewayResponse(headers, HttpStatuses.TOO_MANY_REQUESTS, ByteBuffer.wrap(REJECT_PAYLOAD)));
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

        @Override
        public void onClientResponse(final ClientResponseGatewayExchange exchange)
        {
            final Long remainingTokens = exchange.getAttachment(REMAINING_TOKENS_KEY);
            if (remainingTokens != null)
            {
                exchange.clientResponse().headers().set(HttpHeaders.X_RATELIMIT_LIMIT, this.capacityString);
                exchange.clientResponse().headers().set(HttpHeaders.X_RATELIMIT_REMAINING, String.valueOf(remainingTokens));
            }
        }

        @Override
        public String summary()
        {
            return new StringJoiner(", ", "RateLimiter" + "[", "]")
                    .add("capacity=" + config.capacity())
                    .add("refillTokens=" + config.refillTokens())
                    .add("refillPeriod=" + config.refillPeriod())
                    .toString();
        }
    }
}