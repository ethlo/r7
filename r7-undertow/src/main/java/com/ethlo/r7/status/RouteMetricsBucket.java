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

import com.ethlo.r7.status.dto.EgressDto;
import com.ethlo.r7.status.dto.IngressDto;
import com.ethlo.r7.status.dto.PerformanceTelemetryDto;
import com.ethlo.r7.status.dto.RequestStatsDto;
import com.ethlo.r7.status.dto.RouteMetricsDto;
import com.ethlo.r7.status.dto.TrafficFlowDto;

public final class RouteMetricsBucket
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

    public RouteMetricsBucket(final int capacity, final Duration tickInterval)
    {
        Arrays.setAll(this.clientResponseStatuses, i -> new LongAdder());
        Arrays.setAll(this.upstreamResponseStatuses, i -> new LongAdder());
        this.sparklineRingBuffer = new SparklineRingBuffer(capacity, tickInterval);
    }

    public void incrementTotalRequests() { this.totalRequests.increment(); }
    public void incrementActiveRequests() { this.activeRequests.increment(); }
    public void decrementActiveRequests() { this.activeRequests.decrement(); }
    public void incrementUpstreamRequests() { this.upstreamRequests.increment(); }
    public void incrementActiveWsRequests() { this.activeWsRequests.increment(); }
    public void decrementActiveWsRequests() { this.activeWsRequests.decrement(); }
    public void incrementTotalWsRequests() { this.totalWsRequests.increment(); }
    
    public void setLastActiveTime(final long timestamp)
    {
        this.lastActiveTime.lazySet(timestamp);
    }

    public void addTrafficMetrics(final long reqHeaders, final long reqBody, final long resHeaders, final long resBody, final long journalBytes, final long durationNanos)
    {
        this.totalRequestHeaderBytes.add(reqHeaders);
        this.totalRequestBodyBytes.add(reqBody);
        this.totalResponseHeaderBytes.add(resHeaders);
        this.totalResponseBodyBytes.add(resBody);
        this.totalJournalBytes.add(journalBytes);
        this.totalDurationNanos.add(durationNanos);
    }

    public void recordClientStatus(final int status)
    {
        final int index = Math.max(0, Math.min(status - STATUS_COUNTER_OFFSET, this.clientResponseStatuses.length - 1));
        this.clientResponseStatuses[index].increment();
    }

    public void recordUpstreamStatus(final int status)
    {
        final int index = Math.max(0, Math.min(status - STATUS_COUNTER_OFFSET, this.upstreamResponseStatuses.length - 1));
        this.upstreamResponseStatuses[index].increment();
    }

    public void triggerSparkline()
    {
        this.sparklineRingBuffer.recordTick(getStatus2xxRequests(), getStatus4xxRequests(), getStatus5xxRequests());
    }

    /**
     * Called during a configuration reload to merge a temporary bucket into this persistent one.
     */
    public void merge(final RouteMetricsBucket tempBucket)
    {
        this.totalRequests.add(tempBucket.totalRequests.sumThenReset());
        this.totalWsRequests.add(tempBucket.totalWsRequests.sumThenReset());
        this.activeRequests.add(tempBucket.activeRequests.sumThenReset());
        this.activeWsRequests.add(tempBucket.activeWsRequests.sumThenReset());
        this.upstreamRequests.add(tempBucket.upstreamRequests.sumThenReset());
        this.totalDurationNanos.add(tempBucket.totalDurationNanos.sumThenReset());
        this.totalJournalBytes.add(tempBucket.totalJournalBytes.sumThenReset());
        this.totalRequestHeaderBytes.add(tempBucket.totalRequestHeaderBytes.sumThenReset());
        this.totalRequestBodyBytes.add(tempBucket.totalRequestBodyBytes.sumThenReset());
        this.totalResponseHeaderBytes.add(tempBucket.totalResponseHeaderBytes.sumThenReset());
        this.totalResponseBodyBytes.add(tempBucket.totalResponseBodyBytes.sumThenReset());

        for (int i = 0; i < STATUS_COUNT_ARRAY_SIZE; i++)
        {
            this.clientResponseStatuses[i].add(tempBucket.clientResponseStatuses[i].sumThenReset());
            this.upstreamResponseStatuses[i].add(tempBucket.upstreamResponseStatuses[i].sumThenReset());
        }

        final long tempLastActive = tempBucket.lastActiveTime.get();
        if (tempLastActive > this.lastActiveTime.get())
        {
            this.lastActiveTime.set(tempLastActive);
        }
    }

    public void hydrateFromDto(final RouteMetricsDto routeMetricsDto)
    {
        if (routeMetricsDto == null) { return; }

        final RequestStatsDto requestStatistics = routeMetricsDto.requestStatistics();
        if (requestStatistics != null)
        {
            this.totalRequests.reset();
            this.totalRequests.add(requestStatistics.total());

            this.totalWsRequests.reset();
            this.totalWsRequests.add(requestStatistics.websocketTotal());

            if (requestStatistics.lastActive() != null)
            {
                this.lastActiveTime.set(requestStatistics.lastActive().toInstant().toEpochMilli());
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

    // --- Accessors for Mapping ---

    public long getAvgLatencyNanos()
    {
        final long count = this.totalRequests.sum();
        if (count == 0) { return 0; }
        return this.totalDurationNanos.sum() / count;
    }

    public long getTotalRequests() { return this.totalRequests.sum(); }
    public long getTotalWsRequests() { return this.totalWsRequests.sum(); }
    public long getActiveRequests() { return this.activeRequests.sum(); }
    public long getActiveWsRequests() { return this.activeWsRequests.sum(); }
    public long getUpstreamRequests() { return this.upstreamRequests.sum(); }
    public long getTotalJournalBytes() { return this.totalJournalBytes.sum(); }
    public long getTotalRequestHeaderBytes() { return this.totalRequestHeaderBytes.sum(); }
    public long getTotalRequestBodyBytes() { return this.totalRequestBodyBytes.sum(); }
    public long getTotalResponseHeaderBytes() { return this.totalResponseHeaderBytes.sum(); }
    public long getTotalResponseBodyBytes() { return this.totalResponseBodyBytes.sum(); }
    public SparklineRingBuffer.SparklineSnapshot getSparklineData() { return this.sparklineRingBuffer.getSnapshot(); }

    public OffsetDateTime getLastActiveTime()
    {
        final long time = this.lastActiveTime.get();
        if (time != 0) { return Instant.ofEpochMilli(time).atOffset(ZoneOffset.UTC); }
        return null;
    }

    public Map<Integer, Long> getUpstreamResponseStatuses() { return toMap(this.upstreamResponseStatuses); }
    public Map<Integer, Long> getClientResponseStatuses() { return toMap(this.clientResponseStatuses); }

    public boolean isEmpty() { return totalRequests.sum() == 0; }

    private long summarizeRanges(final int lower, final int upper)
    {
        long total = 0;
        for (int i = lower - STATUS_COUNTER_OFFSET; i <= upper - STATUS_COUNTER_OFFSET; i++)
        {
            total += this.clientResponseStatuses[i].sum();
        }
        return total;
    }

    private long getStatus2xxRequests() { return summarizeRanges(200, 299); }
    private long getStatus4xxRequests() { return summarizeRanges(400, 499); }
    private long getStatus5xxRequests() { return summarizeRanges(STATUS_COUNT_ARRAY_SIZE, 599); }

    private Map<Integer, Long> toMap(final LongAdder[] statuses)
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

    private void fromMap(final LongAdder[] target, final Map<Integer, Long> statuses)
    {
        if (statuses == null) { return; }
        statuses.forEach((key, value) -> {
            final int index = key - STATUS_COUNTER_OFFSET;
            target[index].reset();
            target[index].add(value);
        });
    }
}