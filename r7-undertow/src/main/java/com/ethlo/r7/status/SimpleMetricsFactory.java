package com.ethlo.r7.status;

import java.time.Duration;

import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.SchedulerAware;
import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.CompletedGatewayExchange;
import com.ethlo.r7.api.CompletedGatewayFilter;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.undertow.UndertowGatewayExchange;

public final class SimpleMetricsFactory implements GatewayFilterFactory<GatewayFilterFactory.EmptyConfig>, SchedulerAware
{
    private static final String FILTER_NAME = "SimpleMetrics";
    private GatewayScheduler scheduler;

    @Override
    public String name()
    {
        return FILTER_NAME;
    }

    @Override
    public ClientResponseGatewayFilter create(final EmptyConfig config)
    {
        final Duration tickInterval = Duration.ofSeconds(2);
        final int capacity = 150;

        final GF gf = new GF(capacity, tickInterval);
        this.scheduler.scheduleEvery(tickInterval, gf::trigger);
        return gf;
    }

    @Override
    public void setScheduler(final GatewayScheduler scheduler)
    {
        this.scheduler = scheduler;
    }

    public static final class GF implements ClientRequestGatewayFilter, UpstreamRequestGatewayFilter, ClientResponseGatewayFilter, CompletedGatewayFilter, ShortInfo
    {
        // Volatile ensures the hot-swap by the registry is instantly visible to worker threads
        private volatile RouteMetricsBucket bucket;

        public GF(final int capacity, final Duration tickInterval)
        {
            // Start with a temporary local bucket to ensure no dropped requests during init
            this.bucket = new RouteMetricsBucket(capacity, tickInterval);
        }

        /**
         * Invoked by the MetricsRegistry Matchmaker to link this filter to the persistent store.
         */
        public void linkPersistentBucket(final RouteMetricsBucket persistentBucket)
        {
            if (this.bucket != persistentBucket)
            {
                persistentBucket.merge(this.bucket);
                this.bucket = persistentBucket;
            }
        }

        public RouteMetricsBucket getBucket()
        {
            return this.bucket;
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

        public void trigger()
        {
            this.bucket.triggerSparkline();
        }
    }
}