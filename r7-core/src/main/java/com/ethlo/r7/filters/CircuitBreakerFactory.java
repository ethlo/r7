package com.ethlo.r7.filters;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.CompletedGatewayExchange;
import com.ethlo.r7.api.CompletedGatewayFilter;
import com.ethlo.r7.api.ComponentStatus;
import com.ethlo.r7.api.FallbackAwareGatewayFilter;
import com.ethlo.r7.api.GatewayExchange;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.StateKey;
import com.ethlo.r7.api.StatusReporting;
import com.ethlo.r7.api.UpstreamOutcome;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.doc.Nullable;
import com.ethlo.r7.spi.DeclarationLevel;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ShortCircuitGatewayResponse;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.ethlo.r7.util.constants.MediaTypes;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
@Description("Stops forwarding traffic to a failing upstream until it recovers. Judges each exchange by how its upstream fared, once the exchange is over.")
public final class CircuitBreakerFactory implements GatewayFilterFactory<CircuitBreakerFactory.Config>
{
    private static final byte[] REJECT_PAYLOAD = "Service Unavailable - Circuit Open".getBytes(StandardCharsets.UTF_8);
    private static final String FILTER_NAME = "CircuitBreaker";

    @Override
    public String name()
    {
        return FILTER_NAME;
    }

    /**
     * A breaker guards one upstream; at the root or on a group it would be one breaker for every
     * route beneath, and one failing upstream would open it for all of them.
     */
    @Override
    public Set<DeclarationLevel> levels()
    {
        return Set.of(DeclarationLevel.ROUTE);
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

    public enum State
    {
        CLOSED, OPEN, HALF_OPEN
    }

    public record Config(
            @Description("The number of consecutive failures before the circuit opens.")
            Integer failureThreshold,

            @Description("The duration the circuit remains open before attempting to close.")
            Duration cooldownPeriod,

            @Description("An upstream response head that arrives later than this after the request was forwarded counts as a failure, whatever its status and whether or not the client waited. Without it, slowness counts only through the read timeout.")
            @Nullable
            Duration slowCall) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result)
                    .required("failure_threshold", failureThreshold())
                    .required("cooldown_period", cooldownPeriod());
            if (slowCall() != null)
            {
                // Bounded by what a long of nanoseconds holds, as the filter converts it.
                new ValidatorUtils(result).requirePositive("slow_call", slowCall(), Duration.ofNanos(Long.MAX_VALUE));
            }
        }

        @Override
        public String toString()
        {
            return new StringJoiner(", ", Config.class.getSimpleName() + "[", "]")
                    .add("failure_threshold=" + failureThreshold())
                    .add("cooldown_period=" + cooldownPeriod())
                    .add("slow_call=" + slowCall())
                    .toString();
        }
    }

    /**
     * What this breaker knows of one exchange beyond the upstream's outcome. A key shared by every
     * breaker, not one per instance: a key's id indexes an array in each exchange, and ids are never
     * reused, so a key per instance would grow it with every configuration reload. An exchange can
     * pass several breakers (a route's and a root's), so the marks are told apart by owner.
     * Allocated only for the exchanges that need one: a rejected one, a probe, one that fell back.
     */
    private static final StateKey<Mark> MARKS = new StateKey<>("circuit_breaker.marks");

    private static final class Mark
    {
        final Object owner;
        final Mark next;
        boolean rejected;
        boolean probe;
        boolean fellBack;

        Mark(final Object owner, final Mark next)
        {
            this.owner = owner;
            this.next = next;
        }
    }

    private enum Verdict
    {
        FAILURE, SUCCESS, NEITHER
    }

    private static final class GF implements ClientRequestGatewayFilter, FallbackAwareGatewayFilter, CompletedGatewayFilter, ShortInfo, StatusReporting
    {
        private final Config config;
        private final long cooldownMillis;
        private final int failureThreshold;
        private final long slowCallNanos;

        private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicLong openTimestamp = new AtomicLong(0);
        private final LongAdder rejected = new LongAdder();

        public GF(final Config config)
        {
            this.config = config;
            this.cooldownMillis = config.cooldownPeriod().toMillis();
            this.failureThreshold = config.failureThreshold();
            this.slowCallNanos = config.slowCall() != null ? config.slowCall().toNanos() : Long.MAX_VALUE;
        }

        private Mark mark(final GatewayExchange exchange, final boolean create)
        {
            final Mark head = exchange.getAttachment(MARKS);
            for (Mark mark = head; mark != null; mark = mark.next)
            {
                if (mark.owner == this)
                {
                    return mark;
                }
            }
            if (!create)
            {
                return null;
            }
            final Mark created = new Mark(this, head);
            exchange.setAttachment(MARKS, created);
            return created;
        }

        @Override
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            final State currentState = this.state.get();

            if (currentState == State.OPEN)
            {
                if (System.currentTimeMillis() - this.openTimestamp.get() >= this.cooldownMillis)
                {
                    if (this.state.compareAndSet(State.OPEN, State.HALF_OPEN))
                    {
                        mark(exchange, true).probe = true;
                        return;
                    }
                }
                this.rejectRequest(exchange);
                return;
            }

            if (currentState == State.HALF_OPEN)
            {
                this.rejectRequest(exchange);
            }
        }

        @Override
        public void onFallback(final GatewayExchange exchange)
        {
            mark(exchange, true).fellBack = true;
        }

        /**
         * Judges the exchange by its upstream, not by the status the client got: a filter may have
         * answered in the upstream's place, or changed what it said, and a body can break after a
         * good head.
         */
        @Override
        public void onCompleted(final CompletedGatewayExchange exchange)
        {
            final Mark mark = mark(exchange, false);
            if (mark != null && mark.rejected)
            {
                // The breaker's own refusal says nothing about the upstream.
                return;
            }
            final boolean probe = mark != null && mark.probe;
            switch (verdict(mark, exchange.upstreamOutcome(), exchange))
            {
                case FAILURE -> this.handleFailure(probe);
                case SUCCESS -> this.handleSuccess(probe);
                case NEITHER ->
                {
                    // A probe that proved nothing must not leave the circuit half-open, where every
                    // request is refused and none is left to probe. The cool-down has passed, so the
                    // next request probes.
                    if (probe)
                    {
                        this.state.compareAndSet(State.HALF_OPEN, State.OPEN);
                    }
                }
            }
        }

        private Verdict verdict(final Mark mark, final UpstreamOutcome outcome, final CompletedGatewayExchange exchange)
        {
            // The fallback is the failure, once: what the fallback route then answers is not counted.
            if (mark != null && mark.fellBack)
            {
                return Verdict.FAILURE;
            }
            if (outcome.headNanos() > this.slowCallNanos)
            {
                return Verdict.FAILURE;
            }
            return switch (outcome.kind())
            {
                case NOT_ASKED, CLIENT_ABORTED -> Verdict.NEITHER;
                case NO_RESPONSE, BODY_FAILED -> Verdict.FAILURE;
                case ANSWERED -> HttpStatuses.is5xx(exchange.upstreamResponse().status()) ? Verdict.FAILURE : Verdict.SUCCESS;
            };
        }

        // Only the probe resolves half-open. A request admitted while closed that finishes after
        // the probe started says nothing about whether the upstream has recovered.
        private void handleFailure(final boolean probe)
        {
            final State currentState = this.state.get();

            if (currentState == State.HALF_OPEN)
            {
                if (probe)
                {
                    this.openTimestamp.set(System.currentTimeMillis());
                    this.state.set(State.OPEN);
                }
            }
            else if (currentState == State.CLOSED)
            {
                final int failures = this.consecutiveFailures.incrementAndGet();
                if (failures >= this.failureThreshold)
                {
                    if (this.state.compareAndSet(State.CLOSED, State.OPEN))
                    {
                        this.openTimestamp.set(System.currentTimeMillis());
                    }
                }
            }
        }

        private void handleSuccess(final boolean probe)
        {
            final State currentState = this.state.get();

            if (currentState == State.HALF_OPEN)
            {
                if (probe)
                {
                    this.consecutiveFailures.set(0);
                    this.state.set(State.CLOSED);
                }
            }
            else if (currentState == State.CLOSED)
            {
                this.consecutiveFailures.set(0);
            }
        }

        private void rejectRequest(final ClientRequestGatewayExchange exchange)
        {
            this.rejected.increment();
            mark(exchange, true).rejected = true;
            final MutableGatewayHeaders headers = new MutableFastGatewayHeaders(1)
                    .set(HttpHeaders.CONTENT_TYPE, MediaTypes.TEXT_PLAIN_UTF8);

            exchange.shortCircuit(new ShortCircuitGatewayResponse(headers, HttpStatuses.SERVICE_UNAVAILABLE, ByteBuffer.wrap(REJECT_PAYLOAD)));
        }

        /**
         * The configuration only: the state changes from one request to the next, and is
         * reported by {@link #status()}.
         */
        @Override
        public String summary()
        {
            return new StringJoiner(", ", FILTER_NAME + "[", "]")
                    .add("failure_threshold=" + this.config.failureThreshold())
                    .add("cool_down=" + this.config.cooldownPeriod())
                    .add("slow_call=" + this.config.slowCall())
                    .toString();
        }

        /**
         * Open is an error and half-open a warning: either way the upstream behind it is not
         * getting all the traffic sent to the route. An open circuit stays open past its cool-down
         * until a request arrives to probe with.
         */
        @Override
        public ComponentStatus status()
        {
            final State current = this.state.get();
            final Map<String, Long> values = new LinkedHashMap<>();
            values.put("consecutive_failures", (long) this.consecutiveFailures.get());
            values.put("rejected_requests", this.rejected.sum());
            return switch (current)
            {
                case CLOSED -> new ComponentStatus(ComponentStatus.Health.OK, "Closed", values);
                case HALF_OPEN -> new ComponentStatus(ComponentStatus.Health.WARN, "Half-open: one request is probing the upstream", values);
                case OPEN -> new ComponentStatus(ComponentStatus.Health.ERROR, openSince(this.openTimestamp.get()), values);
            };
        }

        // The state is switched before its timestamp is set: zero is a circuit that has just opened
        private static String openSince(final long openedAt)
        {
            return openedAt == 0 ? "Open" : "Open since " + Instant.ofEpochMilli(openedAt);
        }
    }
}