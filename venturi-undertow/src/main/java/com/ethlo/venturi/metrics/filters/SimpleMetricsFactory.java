package com.ethlo.venturi.metrics.filters;

import java.util.concurrent.atomic.LongAdder;

import com.ethlo.venturi.api.BeforeCommitGatewayExchange;
import com.ethlo.venturi.api.BeforeCommitGatewayFilter;
import com.ethlo.venturi.api.BeforeUpstreamGatewayExchange;
import com.ethlo.venturi.api.BeforeUpstreamGatewayFilter;
import com.ethlo.venturi.api.CompletedGatewayExchange;
import com.ethlo.venturi.api.FinishedGatewayFilter;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.core.ShortInfo;
import com.ethlo.venturi.spi.GatewayFilterFactory;
import com.ethlo.venturi.undertow.UndertowGatewayExchange;

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

    public static final class GF implements BeforeUpstreamGatewayFilter, BeforeCommitGatewayFilter, FinishedGatewayFilter, ShortInfo
    {
        private final LongAdder totalRequests = new LongAdder();
        private final LongAdder activeRequests = new LongAdder();
        private final LongAdder upstreamRequests = new LongAdder();
        private final LongAdder totalDurationNanos = new LongAdder();

        private final LongAdder totalBytesIn = new LongAdder();
        private final LongAdder totalBytesOut = new LongAdder();

        @Override
        public void beforeCommit(final BeforeCommitGatewayExchange exchange)
        {
            this.totalRequests.increment();
            this.activeRequests.increment();
        }

        @Override
        public void beforeUpstream(final BeforeUpstreamGatewayExchange exchange)
        {
            this.upstreamRequests.increment();
        }

        @Override
        public void completed(final CompletedGatewayExchange exchange)
        {
            this.activeRequests.decrement();

            final UndertowGatewayExchange undertowExchange = (UndertowGatewayExchange) exchange;
            final long start = undertowExchange.getRequestStartNanos();
            this.totalDurationNanos.add(System.nanoTime() - start);
            this.totalBytesIn.add(undertowExchange.getTrafficMetrics().totalRequestBytes());
            this.totalBytesOut.add(undertowExchange.getTrafficMetrics().totalResponseBytes());
        }

        @Override
        public String summary()
        {
            return FILTER_NAME;
        }

        public long getAvgLatencyNanos()
        {
            final long count = totalRequests.sum();
            return count == 0 ? 0 : totalDurationNanos.sum() / count;
        }

        public long getTotalRequests()
        {
            return totalRequests.sum();
        }

        public long getActiveRequests()
        {
            return activeRequests.sum();
        }

        public long getUpstreamRequests()
        {
            return upstreamRequests.sum();
        }

        public long getTotalBytesIn()
        {
            return totalBytesIn.sum();
        }

        public long getTotalBytesOut()
        {
            return totalBytesOut.sum();
        }
    }
}