package com.ethlo.venturi.status;

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
import com.ethlo.venturi.util.constants.HttpStatuses;

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
        private final LongAdder total2xxRequests = new LongAdder();
        private final LongAdder total3xxRequests = new LongAdder();
        private final LongAdder total4xxRequests = new LongAdder();
        private final LongAdder total5xxRequests = new LongAdder();

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
            totalRequests.increment();
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
            this.totalRequestHeaderBytes.add(undertowExchange.getTrafficMetrics().requestHeaderBytes());
            this.totalRequestBodyBytes.add(undertowExchange.getTrafficMetrics().requestBodyBytes());
            this.totalResponseHeaderBytes.add(undertowExchange.getTrafficMetrics().responseHeaderBytes());
            this.totalResponseBodyBytes.add(undertowExchange.getTrafficMetrics().responseBodyBytes());
            this.totalJournalBytes.add(undertowExchange.getJournalBytes());
            this.totalDurationNanos.add(undertowExchange.getDurationNanos());

            final int statusCode = exchange.clientResponse().status();
            if (HttpStatuses.is2xx(statusCode))
            {
                total2xxRequests.increment();
            }
            else if (HttpStatuses.is3xx(statusCode))
            {
                total3xxRequests.increment();
            }
            else if (HttpStatuses.is4xx(statusCode))
            {
                total4xxRequests.increment();
            }
            else if (HttpStatuses.is5xx(statusCode))
            {
                total5xxRequests.increment();
            }
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

        public long getStatus2xxRequests()
        {
            return total2xxRequests.sum();
        }

        public long getStatus3xxRequests()
        {
            return total3xxRequests.sum();
        }

        public long getStatus4xxRequests()
        {
            return total4xxRequests.sum();
        }

        public long getStatus5xxRequests()
        {
            return total5xxRequests.sum();
        }
    }
}