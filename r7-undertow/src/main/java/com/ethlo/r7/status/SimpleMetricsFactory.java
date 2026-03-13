package com.ethlo.r7.status;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.SchedulerAware;
import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.CompletedGatewayExchange;
import com.ethlo.r7.api.CompletedGatewayFilter;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.status.dto.EgressDto;
import com.ethlo.r7.status.dto.IngressDto;
import com.ethlo.r7.status.dto.PerformanceTelemetryDto;
import com.ethlo.r7.status.dto.RequestStatsDto;
import com.ethlo.r7.status.dto.RouteMetricsDto;
import com.ethlo.r7.status.dto.TrafficFlowDto;
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
        scheduler.scheduleEvery(tickInterval, gf::trigger);
        return gf;
    }

    @Override
    public void setScheduler(final GatewayScheduler scheduler)
    {
        this.scheduler = scheduler;
    }

    public static final class GF implements ClientRequestGatewayFilter, UpstreamRequestGatewayFilter, ClientResponseGatewayFilter, CompletedGatewayFilter, ShortInfo
    {
        public static final int STATUS_COUNT_ARRAY_SIZE = 500;
        private static final int STATUS_COUNTER_OFFSET = 100;
        private final AtomicLong lastActiveTime = new AtomicLong(0L);
        private final LongAdder totalRequests = new LongAdder();
        private final LongAdder totalWsRequests = new LongAdder();
        private final LongAdder[] clientResponseStatuses = new LongAdder[STATUS_COUNT_ARRAY_SIZE];
        private final LongAdder[] upstreamResponseStatuses = new LongAdder[STATUS_COUNT_ARRAY_SIZE];
        private final LongAdder activeRequests = new LongAdder();
        private final LongAdder activeWsRequests = new LongAdder();
        private final LongAdder upstreamRequests = new LongAdder();
        private final LongAdder totalDurationNanos = new LongAdder();
        private final LongAdder totalJournalBytes = new LongAdder();
        private final LongAdder totalRequestHeaderBytes = new LongAdder();
        private final LongAdder totalRequestBodyBytes = new LongAdder();
        private final LongAdder totalResponseHeaderBytes = new LongAdder();
        private final LongAdder totalResponseBodyBytes = new LongAdder();
        private final SparklineRingBuffer sparklineRingBuffer;

        public GF(final int capacity, Duration tickInterval)
        {
            Arrays.setAll(this.clientResponseStatuses, i -> new LongAdder());
            Arrays.setAll(this.upstreamResponseStatuses, i -> new LongAdder());
            sparklineRingBuffer = new SparklineRingBuffer(capacity, tickInterval);
        }

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

            final int clientStatus = exchange.clientResponse() != null ? exchange.clientResponse().status() : 0;
            // Bound the index between 0 and 499 to prevent crashes on weird internal status codes
            final int clientIndex = Math.max(0, Math.min(clientStatus - STATUS_COUNTER_OFFSET, this.clientResponseStatuses.length - 1));
            this.clientResponseStatuses[clientIndex].increment();

            // Safe Upstream Status Logging
            if (undertowExchange.wasProxied())
            {
                final int upstreamStatus = exchange.upstreamResponse().status();
                final int upstreamIndex = Math.max(0, Math.min(upstreamStatus - STATUS_COUNTER_OFFSET, this.upstreamResponseStatuses.length - 1));
                this.upstreamResponseStatuses[upstreamIndex].increment();
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

        private long summarizeRanges(int lower, int upper)
        {
            long total = 0;
            for (int i = lower - STATUS_COUNTER_OFFSET; i <= upper - STATUS_COUNTER_OFFSET; i++)
            {
                total += clientResponseStatuses[i].sum();
            }
            return total;
        }

        public long getStatus2xxRequests()
        {
            return summarizeRanges(200, 299);
        }

        public long getStatus3xxRequests()
        {
            return summarizeRanges(300, 399);
        }

        public long getStatus4xxRequests()
        {
            return summarizeRanges(400, 499);
        }

        public long getStatus5xxRequests()
        {
            return summarizeRanges(STATUS_COUNT_ARRAY_SIZE, 599);
        }

        public OffsetDateTime getLastActiveTime()
        {
            if (this.lastActiveTime.get() != 0)
            {
                return Instant.ofEpochMilli(this.lastActiveTime.get()).atOffset(ZoneOffset.UTC);
            }
            return null;
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

                this.totalWsRequests.reset();
                this.totalWsRequests.add(requestStatistics.websocketTotal());

                if (requestStatistics.lastActive() != null)
                {
                    final long epochNanos = requestStatistics.lastActive().toInstant().toEpochMilli();
                    this.lastActiveTime.set(epochNanos);
                }

                fromMap(this.upstreamResponseStatuses, requestStatistics.upstreamResponseStatuses());
                fromMap(this.clientResponseStatuses, requestStatistics.clientResponseStatuses());

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
            if (performanceTelemetry != null && performanceTelemetry.averageLatency() != null && requestStatistics != null)
            {
                this.totalDurationNanos.reset();
                this.totalDurationNanos.add(performanceTelemetry.averageLatency().toNanos() * requestStatistics.total());
            }
        }

        public Map<Integer, Long> getUpstreamResponseStatuses()
        {
            return toMap(this.upstreamResponseStatuses);
        }

        public Map<Integer, Long> getClientResponseStatuses()
        {
            return toMap(this.clientResponseStatuses);
        }

        public SparklineRingBuffer.SparklineSnapshot getSparklineData()
        {
            return sparklineRingBuffer.getSnapshot();
        }

        private void trigger()
        {
            this.sparklineRingBuffer.recordTick(getStatus2xxRequests(), getStatus4xxRequests(), getStatus5xxRequests());
        }

        private Map<Integer, Long> toMap(LongAdder[] statuses)
        {
            final Map<Integer, Long> result = new TreeMap<>();
            for (int i = 0; i < statuses.length; i++)
            {
                final long count = statuses[i].sum();
                if (count > 0L)
                {
                    result.put(i + STATUS_COUNTER_OFFSET, count);
                }
            }
            return result;
        }

        private void fromMap(final LongAdder[] target, Map<Integer, Long> statuses)
        {
            if (statuses == null)
            {
                return;
            }

            statuses.forEach((key, value) ->
            {
                final int index = key - STATUS_COUNTER_OFFSET;
                target[index].reset();
                target[index].add(value);
            });
        }
    }
}