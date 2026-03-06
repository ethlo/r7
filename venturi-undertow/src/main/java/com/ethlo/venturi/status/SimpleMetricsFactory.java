package com.ethlo.venturi.status;

import com.ethlo.venturi.api.*;
import com.ethlo.venturi.core.ShortInfo;
import com.ethlo.venturi.spi.GatewayFilterFactory;
import com.ethlo.venturi.status.dto.*;
import com.ethlo.venturi.undertow.UndertowGatewayExchange;
import com.ethlo.venturi.util.constants.HttpStatuses;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class SimpleMetricsFactory implements GatewayFilterFactory<GatewayFilter, GatewayFilterFactory.EmptyConfig>
{
    private static final String FILTER_NAME = "SimpleMetrics";

    @Override
    public String name()
    {
        return FILTER_NAME;
    }

    @Override
    public BeforeCommitGatewayFilter create(final EmptyConfig config)
    {
        return new GF();
    }

    public static final class GF implements ClientRequestGatewayFilter, BeforeUpstreamGatewayFilter, BeforeCommitGatewayFilter, FinishedGatewayFilter, ShortInfo
    {
        private final AtomicLong lastActiveTime = new AtomicLong(0L);

        private final LongAdder totalRequests = new LongAdder();
        private final LongAdder totalWsRequests = new LongAdder();
        private final LongAdder total2xxRequests = new LongAdder();
        private final LongAdder total3xxRequests = new LongAdder();
        private final LongAdder total4xxRequests = new LongAdder();
        private final LongAdder total5xxRequests = new LongAdder();

        private final LongAdder activeRequests = new LongAdder();
        private final LongAdder activeWsRequests = new LongAdder();
        private final LongAdder upstreamRequests = new LongAdder();
        private final LongAdder totalDurationNanos = new LongAdder();

        private final LongAdder totalJournalBytes = new LongAdder();

        private final LongAdder totalRequestHeaderBytes = new LongAdder();
        private final LongAdder totalRequestBodyBytes = new LongAdder();
        private final LongAdder totalResponseHeaderBytes = new LongAdder();
        private final LongAdder totalResponseBodyBytes = new LongAdder();


        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            this.totalRequests.increment();
            this.activeRequests.increment();
        }

        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            this.upstreamRequests.increment();
        }

        @Override
        public void onClientResponse(final ClientResponseGatewayExchange exchange)
        {
            final UndertowGatewayExchange undertowExchange = (UndertowGatewayExchange) exchange;
            if (undertowExchange.isWebsocketUpgraded())
            {
                undertowExchange.onWebSocketClose(this.activeWsRequests::decrement);
                this.activeWsRequests.increment();
                this.totalWsRequests.increment();
            }

            this.lastActiveTime.lazySet(System.currentTimeMillis());
        }

        @Override
        public void onCompleted(final CompletedGatewayExchange exchange)
        {
            final UndertowGatewayExchange undertowExchange = (UndertowGatewayExchange) exchange;

            this.activeRequests.decrement();

            this.totalRequestHeaderBytes.add(undertowExchange.getTrafficMetrics().requestHeaderBytes());
            this.totalRequestBodyBytes.add(undertowExchange.getTrafficMetrics().requestBodyBytes());
            this.totalResponseHeaderBytes.add(undertowExchange.getTrafficMetrics().responseHeaderBytes());
            this.totalResponseBodyBytes.add(undertowExchange.getTrafficMetrics().responseBodyBytes());
            this.totalJournalBytes.add(undertowExchange.getJournalBytes());
            this.totalDurationNanos.add(undertowExchange.getDurationNanos());

            final int statusCode = exchange.clientResponse().status();
            if (HttpStatuses.is2xx(statusCode))
            {
                this.total2xxRequests.increment();
            }
            else if (HttpStatuses.is3xx(statusCode))
            {
                this.total3xxRequests.increment();
            }
            else if (HttpStatuses.is4xx(statusCode))
            {
                this.total4xxRequests.increment();
            }
            else if (HttpStatuses.is5xx(statusCode))
            {
                this.total5xxRequests.increment();
            }
        }

        @Override
        public String summary()
        {
            return FILTER_NAME;
        }

        public long getAvgLatencyNanos()
        {
            final long count = this.totalRequests.sum();
            if (count == 0)
            {
                return 0;
            }
            return this.totalDurationNanos.sum() / count;
        }

        public long getTotalRequests()
        {
            return this.totalRequests.sum();
        }

        public long getTotalWsRequests()
        {
            return this.totalWsRequests.sum();
        }

        public long getActiveRequests()
        {
            return this.activeRequests.sum();
        }

        public long getActiveWsRequests()
        {
            return this.activeWsRequests.sum();
        }

        public long getUpstreamRequests()
        {
            return this.upstreamRequests.sum();
        }

        public long getTotalJournalBytes()
        {
            return this.totalJournalBytes.sum();
        }

        public long getTotalRequestHeaderBytes()
        {
            return this.totalRequestHeaderBytes.sum();
        }

        public long getTotalRequestBodyBytes()
        {
            return this.totalRequestBodyBytes.sum();
        }

        public long getTotalResponseHeaderBytes()
        {
            return this.totalResponseHeaderBytes.sum();
        }

        public long getTotalResponseBodyBytes()
        {
            return this.totalResponseBodyBytes.sum();
        }

        public long getStatus2xxRequests()
        {
            return this.total2xxRequests.sum();
        }

        public long getStatus3xxRequests()
        {
            return this.total3xxRequests.sum();
        }

        public long getStatus4xxRequests()
        {
            return this.total4xxRequests.sum();
        }

        public long getStatus5xxRequests()
        {
            return this.total5xxRequests.sum();
        }

        public long getLastActiveTime()
        {
            return this.lastActiveTime.get();
        }

        public void set(final RouteMetricsDto routeMetricsDto)
        {
            if (routeMetricsDto == null)
            {
                return;
            }

            final RequestStatsDto requestStatistics = routeMetricsDto.requestStatistics();
            if (requestStatistics != null)
            {
                this.totalRequests.reset();
                this.totalRequests.add(requestStatistics.total());

                this.activeRequests.reset();
                this.activeRequests.add(requestStatistics.active());

                this.totalWsRequests.reset();
                this.totalWsRequests.add(requestStatistics.websocketTotal());

                this.activeWsRequests.reset();
                this.activeWsRequests.add(requestStatistics.websocketActive());

                this.lastActiveTime.set(requestStatistics.lastActive());

                this.total2xxRequests.reset();
                this.total2xxRequests.add(requestStatistics.status2xx());

                this.total3xxRequests.reset();
                this.total3xxRequests.add(requestStatistics.status3xx());

                this.total4xxRequests.reset();
                this.total4xxRequests.add(requestStatistics.status4xx());

                this.total5xxRequests.reset();
                this.total5xxRequests.add(requestStatistics.status5xx());

                this.upstreamRequests.reset();
                this.upstreamRequests.add(requestStatistics.upstream());
            }

            final TrafficFlowDto trafficFlow = routeMetricsDto.trafficFlow();
            if (trafficFlow != null)
            {
                this.totalJournalBytes.reset();
                this.totalJournalBytes.add(trafficFlow.journalStorageBytes());

                final IngressDto ingress = trafficFlow.ingress();
                if (ingress != null)
                {
                    this.totalRequestHeaderBytes.reset();
                    this.totalRequestHeaderBytes.add(ingress.headerBytes());

                    this.totalRequestBodyBytes.reset();
                    this.totalRequestBodyBytes.add(ingress.bodyBytes());
                }

                final EgressDto egress = trafficFlow.egress();
                if (egress != null)
                {
                    this.totalResponseHeaderBytes.reset();
                    this.totalResponseHeaderBytes.add(egress.headerBytes());

                    this.totalResponseBodyBytes.reset();
                    this.totalResponseBodyBytes.add(egress.bodyBytes());
                }
            }

            final PerformanceTelemetryDto performanceTelemetry = routeMetricsDto.performanceTelemetry();
            if (performanceTelemetry != null && requestStatistics != null)
            {
                this.totalDurationNanos.reset();
                this.totalDurationNanos.add(performanceTelemetry.averageLatencyNanoseconds() * requestStatistics.total());
            }
        }
    }
}