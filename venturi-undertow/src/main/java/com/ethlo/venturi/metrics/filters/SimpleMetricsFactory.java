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

        private final LongAdder totalJournalBytes = new LongAdder();

        private final LongAdder totalRequestHeaderBytes = new LongAdder();
        private final LongAdder totalRequestBodyBytes = new LongAdder();
        private final LongAdder totalResponseHeaderBytes = new LongAdder();
        private final LongAdder totalResponseBodyBytes = new LongAdder();

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
            this.totalRequestHeaderBytes.add(undertowExchange.getTrafficMetrics().requestHeaderBytes());
            this.totalRequestBodyBytes.add(undertowExchange.getTrafficMetrics().requestBodyBytes());
            this.totalResponseHeaderBytes.add(undertowExchange.getTrafficMetrics().responseHeaderBytes());
            this.totalResponseBodyBytes.add(undertowExchange.getTrafficMetrics().responseBodyBytes());
            this.totalJournalBytes.add(undertowExchange.getJournalBytes());
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

        public long getTotalJournalBytes()
        {
            return totalJournalBytes.sum();
        }

        public long getTotalRequestHeaderBytes()
        {
            return totalRequestHeaderBytes.sum();
        }

        public long getTotalRequestBodyBytes()
        {
            return totalRequestBodyBytes.sum();
        }

        public long getTotalResponseHeaderBytes()
        {
            return totalResponseHeaderBytes.sum();
        }

        public long getTotalResponseBodyBytes()
        {
            return totalResponseBodyBytes.sum();
        }
    }
}