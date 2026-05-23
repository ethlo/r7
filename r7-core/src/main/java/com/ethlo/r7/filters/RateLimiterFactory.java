package com.ethlo.r7.filters;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.StateKey;
import com.ethlo.r7.core.GatewayContextKeys;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.FastTerminationGatewayResponse;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.auto.service.AutoService;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
public final class RateLimiterFactory implements GatewayFilterFactory<RateLimiterFactory.Config>
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
    public ClientRequestGatewayFilter create(final Config config, final FilterCreationContext filterCreationContext)
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
                    .required("capacity", capacity())
                    .required("refill_tokens", refillTokens())
                    .required("refill_period", refillPeriod());
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

    private static final class GF implements ClientRequestGatewayFilter, ClientResponseGatewayFilter, ShortInfo
    {
        private final Cache<String, Bucket> buckets;
        private final Config config;
        private final String capacityString;

        public GF(final Config config)
        {
            this.config = config;
            this.capacityString = Long.toString(config.capacity());
            this.buckets = Caffeine.newBuilder()
                    .maximumSize(config.maxBuckets())
                    .expireAfterAccess(config.maxBucketTTL())
                    .build();
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            final String customKey = exchange.getAttachment(GatewayContextKeys.RATE_LIMIT_KEY);
            final String key = (customKey != null) ? customKey : exchange.clientRequest().remoteAddress().getHostAddress();

            final Bucket bucket = this.buckets.get(key, this::createNewBucket);
            final ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1L);
            exchange.setAttachment(REMAINING_TOKENS_KEY, probe.getRemainingTokens());

            if (!probe.isConsumed())
            {
                final long waitNanos = probe.getNanosToWaitForRefill();
                final long waitSeconds = TimeUnit.NANOSECONDS.toSeconds(waitNanos) + 1L;

                final MutableGatewayHeaders headers = new MutableFastGatewayHeaders(3)
                        .set(HttpHeaders.RETRY_AFTER, String.valueOf(waitSeconds))
                        .set(HttpHeaders.X_RATELIMIT_LIMIT, this.capacityString)
                        .set(HttpHeaders.X_RATELIMIT_REMAINING, "0");

                exchange.shortCircuit(new FastTerminationGatewayResponse(headers, HttpStatuses.TOO_MANY_REQUESTS, ByteBuffer.wrap(REJECT_PAYLOAD)));
            }
        }

        private Bucket createNewBucket(final String key)
        {
            final Bandwidth limit = Bandwidth.builder()
                    .capacity(this.config.capacity())
                    .refillGreedy(this.config.refillTokens(), this.config.refillPeriod())
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
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return new StringJoiner(", ", FILTER_NAME + "[", "]")
                    .add("capacity=" + this.config.capacity())
                    .add("refill_tokens=" + this.config.refillTokens())
                    .add("refill_period=" + this.config.refillPeriod())
                    .toString();
        }
    }
}