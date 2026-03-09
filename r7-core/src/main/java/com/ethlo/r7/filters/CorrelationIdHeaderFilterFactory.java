package com.ethlo.r7.filters;

import static com.ethlo.r7.util.constants.HttpHeaders.X_CORRELATION_ID;

import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.core.ShortInfo;
import com.ethlo.r7.spi.GatewayFilterFactory;

public class CorrelationIdHeaderFilterFactory implements GatewayFilterFactory<GatewayFilterFactory.EmptyConfig>
{
    private static final String FILTER_NAME = "CorrelationIdHeader";

    @Override
    public String name()
    {
        return FILTER_NAME;
    }

    @Override
    public GatewayFilter create(EmptyConfig config)
    {
        return new GF();
    }

    private static class GF implements UpstreamRequestGatewayFilter, ClientResponseGatewayFilter, ShortInfo
    {
        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            exchange.upstreamRequest().headers().add(X_CORRELATION_ID, exchange.requestId());
        }

        @Override
        public void onClientResponse(final ClientResponseGatewayExchange exchange)
        {
            exchange.clientResponse().headers().add(X_CORRELATION_ID, exchange.requestId());
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + X_CORRELATION_ID;
        }
    }
}