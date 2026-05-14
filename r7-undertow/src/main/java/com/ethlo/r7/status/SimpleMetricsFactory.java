package com.ethlo.r7.status;

import java.time.Duration;
import java.util.Optional;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.CompletedGatewayExchange;
import com.ethlo.r7.api.CompletedGatewayFilter;
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
        final RouteMetricsBucket persistentBucket = metricsRegistry.getOrCreate(
                filterCreationContext.routeId(),
                config.capacity(),
                config.interval()
        );
        return new GF(persistentBucket);
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
        private final RouteMetricsBucket bucket;

        public GF(final RouteMetricsBucket routeMetricsBucket)
        {
            this.bucket = routeMetricsBucket;
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            this.bucket.incrementTotalRequests();
            this.bucket.incrementActiveRequests();
        }

        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            this.bucket.incrementUpstreamRequests();
        }

        @Override
        public void onClientResponse(final ClientResponseGatewayExchange exchange)
        {
            final UndertowGatewayExchange undertowExchange = (UndertowGatewayExchange) exchange;
            if (undertowExchange.isWebsocketUpgraded())
            {
                undertowExchange.onWebSocketClose(this.bucket::decrementActiveWsRequests);
                this.bucket.incrementActiveWsRequests();
                this.bucket.incrementTotalWsRequests();
            }

            this.bucket.setLastActiveTime(System.currentTimeMillis());
        }

        @Override
        public void onCompleted(final CompletedGatewayExchange exchange)
        {
            final UndertowGatewayExchange undertowExchange = (UndertowGatewayExchange) exchange;
            this.bucket.decrementActiveRequests();

            this.bucket.addTrafficMetrics(
                    undertowExchange.getTrafficMetrics().requestHeaderBytes(),
                    undertowExchange.getTrafficMetrics().requestBodyBytes(),
                    undertowExchange.getTrafficMetrics().responseHeaderBytes(),
                    undertowExchange.getTrafficMetrics().responseBodyBytes(),
                    undertowExchange.getJournalBytes(),
                    undertowExchange.getDurationNanos()
            );

            final int clientStatus = exchange.clientResponse() != null ? exchange.clientResponse().status() : 0;
            this.bucket.recordClientStatus(clientStatus);

            if (undertowExchange.wasProxied())
            {
                this.bucket.recordUpstreamStatus(exchange.upstreamResponse().status());
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