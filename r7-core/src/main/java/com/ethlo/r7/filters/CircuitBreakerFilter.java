package com.ethlo.r7.filters;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.FastTerminationGatewayResponse;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public final class CircuitBreakerFilter implements GatewayFilterFactory<CircuitBreakerFilter.Config>
{
    private static final byte[] REJECT_PAYLOAD = "Service Unavailable - Circuit Open".getBytes(StandardCharsets.UTF_8);
    private static final String FILTER_NAME = "CircuitBreaker";

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
    public ClientRequestGatewayFilter create(final Config config, FilterCreationContext filterCreationContext)
    {
        return new GF(config);
    }

    public enum State
    {
        CLOSED, OPEN, HALF_OPEN
    }

    public record Config(Integer failureThreshold, Duration cooldownPeriod) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result)
                    .required(FILTER_NAME, "failure_threshold", failureThreshold)
                    .required(FILTER_NAME, "cooldown_period", cooldownPeriod);
        }

        @Override
        public String toString()
        {
            return new StringJoiner(", ", Config.class.getSimpleName() + "[", "]")
                    .add("failure_threshold=" + failureThreshold())
                    .add("cooldown_period=" + cooldownPeriod())
                    .toString();
        }
    }

    private static class GF implements ClientRequestGatewayFilter, ClientResponseGatewayFilter, ShortInfo
    {
        private final Config config;
        private final long cooldownMillis;
        private final int failureThreshold;

        private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicLong openTimestamp = new AtomicLong(0);

        public GF(final Config config)
        {
            this.config = config;
            this.cooldownMillis = config.cooldownPeriod().toMillis();
            this.failureThreshold = config.failureThreshold();
        }

        @Override
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            final State currentState = state.get();

            if (currentState == State.OPEN)
            {
                if (System.currentTimeMillis() - openTimestamp.get() >= cooldownMillis)
                {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN))
                    {
                        // Allow this single request to proceed as a probe
                        return;
                    }
                }

                // Fast-fail in microseconds
                rejectRequest(exchange);
                return;
            }

            if (currentState == State.HALF_OPEN)
            {
                // Another thread is already probing; reject subsequent requests
                rejectRequest(exchange);
            }
        }

        @Override
        public void onClientResponse(final ClientResponseGatewayExchange exchange)
        {
            if (HttpStatuses.is5xx(exchange.clientResponse().status()))
            {
                handleFailure();
            }
            else
            {
                handleSuccess();
            }
        }

        private void handleFailure()
        {
            final State currentState = state.get();

            if (currentState == State.HALF_OPEN)
            {
                // The probe failed. Snap immediately back to OPEN and reset the cooldown clock.
                openTimestamp.set(System.currentTimeMillis());
                state.set(State.OPEN);
            }
            else if (currentState == State.CLOSED)
            {
                // Normal operation failed. Increment counter and trip if threshold is reached.
                final int failures = consecutiveFailures.incrementAndGet();
                if (failures >= failureThreshold)
                {
                    if (state.compareAndSet(State.CLOSED, State.OPEN))
                    {
                        openTimestamp.set(System.currentTimeMillis());
                    }
                }
            }
        }

        private void handleSuccess()
        {
            final State currentState = state.get();

            if (currentState == State.HALF_OPEN)
            {
                // The probe succeeded! The upstream is healthy again.
                consecutiveFailures.set(0);
                state.set(State.CLOSED);
            }
            else if (currentState == State.CLOSED)
            {
                // Reset consecutive failures on any success
                consecutiveFailures.set(0);
            }
        }

        private void rejectRequest(final ClientRequestGatewayExchange exchange)
        {
            final MutableGatewayHeaders headers = new MutableFastGatewayHeaders(1)
                    .set(HttpHeaders.CONTENT_TYPE, "text/plain");

            exchange.shortCircuit(new FastTerminationGatewayResponse(headers, HttpStatuses.SERVICE_UNAVAILABLE, ByteBuffer.wrap(REJECT_PAYLOAD)));
        }

        @Override
        public String summary()
        {
            return new StringJoiner(", ", "CircuitBreaker" + "[", "]")
                    .add("failure_threshold=" + config.failureThreshold())
                    .add("cool_down=" + config.cooldownPeriod())
                    .add("state=" + state.get().name())
                    .toString();
        }
    }
}