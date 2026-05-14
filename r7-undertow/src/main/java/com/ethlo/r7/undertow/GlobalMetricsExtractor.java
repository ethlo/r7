package com.ethlo.r7.undertow;

import io.undertow.Undertow;
import io.undertow.server.ConnectorStatistics;

public final class GlobalMetricsExtractor
{
    private final Undertow server;

    public GlobalMetricsExtractor(final Undertow server)
    {
        this.server = server;
    }

    public GlobalStats getStats()
    {
        // Assuming a single HTTP listener on index 0
        final ConnectorStatistics stats = this.server.getListenerInfo().getFirst().getConnectorStatistics();
        
        if (stats != null)
        {
            final long unroutableErrors = stats.getErrorCount();
            final long globalTotalRequests = stats.getRequestCount();
            final long globalActiveConnections = stats.getActiveConnections();
            return new GlobalStats(globalTotalRequests, unroutableErrors, globalActiveConnections);
        }
        
        return new GlobalStats(0L, 0L, 0L);
    }
    
    public record GlobalStats(long totalRequests, long unroutableErrors, long activeConnections) 
    {
    }
}