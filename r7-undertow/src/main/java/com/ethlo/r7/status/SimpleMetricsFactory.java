package com.ethlo.r7.status;

import java.time.Duration;
import java.util.Optional;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.CompletedGatewayExchange;
import com.ethlo.r7.api.CompletedGatewayFilter;
import com.ethlo.r7.api.GatewayExchange;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.undertow.UndertowGatewayExchange;
import com.ethlo.r7.validation.ValidatableConfig;

public final class SimpleMetricsFactory implements GatewayFilterFactory<SimpleMetricsFactory.Config>
{
    private static final String FILTER_NAME = "SimpleMetrics";

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
    public ClientResponseGatewayFilter create(final Config config, final FilterCreationContext filterCreationContext)
    {
        final MetricsRegistry metricsRegistry = filterCreationContext.engine().getRequired(MetricsRegistry.class);
        return new GF(metricsRegistry, config);
    }

    public record Config(Duration period, Duration interval) implements ValidatableConfig
    {
        @Override
        public Duration period()
        {
            return Optional.ofNullable(period).orElse(Duration.ofMinutes(2));
        }

        @Override
        public Duration interval()
        {
            return Optional.ofNullable(interval).orElse(Duration.ofSeconds(1));
        }

        public int capacity()
        {
            return (int) period().dividedBy(interval());
        }
    }

    public static final class GF implements ClientRequestGatewayFilter, UpstreamRequestGatewayFilter, ClientResponseGatewayFilter, CompletedGatewayFilter, ShortInfo
    {
        private final MetricsRegistry metricsRegistry;
        private final Config config;

        public GF(final MetricsRegistry metricsRegistry, final Config config)
        {
            this.metricsRegistry = metricsRegistry;
            this.config = config;
        }

        /**
         * Resolved per exchange, by the route that actually matched it, rather than bound once
         * at construction time: when this filter is declared in {@code global_filters}, one
         * instance is now shared by every route (see M5 in the filter-runtime review), so a
         * bucket fixed at creation would put every route's traffic into whichever single route
         * happened to be in scope when the shared instance was built - the fallback route's own
         * declaration is the one exception, since a fallback runs with the matched route's own
         * started instances (see DefaultGatewayRoute#asFallbackOfThis), not this method.
         * Route-scoped (non-global) declarations still get one instance per route, so this
         * always resolves to the same bucket for them; the lookup is a plain map access, not an
         * allocation.
         */
        private RouteMetricsBucket bucketFor(final GatewayExchange exchange)
        {
            return this.metricsRegistry.getOrCreate(exchange.route().id(), this.config.capacity(), this.config.interval());
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            final RouteMetricsBucket bucket = this.bucketFor(exchange);
            bucket.incrementTotalRequests();
            bucket.incrementActiveRequests();
        }

        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            this.bucketFor(exchange).incrementUpstreamRequests();
        }

        @Override
        public void onClientResponse(final ClientResponseGatewayExchange exchange)
        {
            final UndertowGatewayExchange undertowExchange = (UndertowGatewayExchange) exchange;
            final RouteMetricsBucket bucket = this.bucketFor(exchange);
            if (undertowExchange.isWebsocketUpgraded())
            {
                undertowExchange.onWebSocketClose(bucket::decrementActiveWsRequests);
                bucket.incrementActiveWsRequests();
                bucket.incrementTotalWsRequests();
            }

            bucket.setLastActiveTime(System.currentTimeMillis());
        }

        @Override
        public void onCompleted(final CompletedGatewayExchange exchange)
        {
            final UndertowGatewayExchange undertowExchange = (UndertowGatewayExchange) exchange;
            final RouteMetricsBucket bucket = this.bucketFor(exchange);
            bucket.decrementActiveRequests();

            bucket.addTrafficMetrics(
                    undertowExchange.getTrafficMetrics().requestHeaderBytes(),
                    undertowExchange.getTrafficMetrics().requestBodyBytes(),
                    undertowExchange.getTrafficMetrics().responseHeaderBytes(),
                    undertowExchange.getTrafficMetrics().responseBodyBytes(),
                    undertowExchange.getJournalBytes(),
                    undertowExchange.getDurationNanos()
            );

            final int clientStatus = exchange.clientResponse() != null ? exchange.clientResponse().status() : 0;
            bucket.recordClientStatus(clientStatus);

            if (undertowExchange.wasProxied())
            {
                bucket.recordUpstreamStatus(exchange.upstreamResponse().status());
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
            return FILTER_NAME;
        }
    }
}