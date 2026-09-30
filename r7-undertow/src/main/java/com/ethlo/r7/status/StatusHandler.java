package com.ethlo.r7.status;

import java.nio.ByteBuffer;
import java.util.Map;

import com.ethlo.r7.status.dto.ConnectorStatisticsDto;
import io.undertow.server.ConnectorStatistics;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

/**
 * The management port on Undertow: the {@link ManagementEndpoint} every r7 server shares, plus
 * Undertow's listener counters.
 */
public final class StatusHandler implements HttpHandler
{
    private final ManagementEndpoint endpoint;

    public StatusHandler(final ManagementEndpoint endpoint)
    {
        this.endpoint = endpoint;
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange)
    {
        if (exchange.isInIoThread())
        {
            exchange.dispatch(this);
            return;
        }
        final ManagementEndpoint.Response response = this.endpoint.handle(exchange.getRequestMethod().toString(),
                exchange.getRequestHeaders().getFirst(Headers.HOST), exchange.getRequestHeaders().getFirst(Headers.ACCEPT));
        exchange.setStatusCode(response.status());
        for (final Map.Entry<String, String> header : response.headers().entrySet())
        {
            exchange.getResponseHeaders().put(HttpString.tryFromString(header.getKey()), header.getValue());
        }
        if (response.body().length == 0)
        {
            exchange.endExchange();
            return;
        }
        exchange.getResponseSender().send(ByteBuffer.wrap(response.body()));
    }

    /**
     * Undertow's listener counters as the dashboard's DTO. Here rather than in ModelMapper, which
     * is server-neutral: the counters are Undertow's type, and so is the mapping.
     */
    public static ConnectorStatisticsDto toDto(final ConnectorStatistics stats)
    {
        if (stats == null)
        {
            return null;
        }

        return new ConnectorStatisticsDto(
                stats.getRequestCount(),
                stats.getBytesSent(),
                stats.getBytesReceived(),
                stats.getErrorCount(),
                stats.getProcessingTime(),
                stats.getMaxProcessingTime(),
                stats.getActiveConnections(),
                stats.getMaxActiveConnections(),
                stats.getActiveRequests(),
                stats.getMaxActiveRequests()
        );
    }
}
