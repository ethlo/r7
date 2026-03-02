package com.ethlo.venturi.core.filters;

import static com.ethlo.venturi.util.constants.HttpHeaders.X_CORRELATION_ID;

import com.ethlo.venturi.api.BeforeCommitGatewayExchange;
import com.ethlo.venturi.api.BeforeCommitGatewayFilter;
import com.ethlo.venturi.api.BeforeUpstreamGatewayExchange;
import com.ethlo.venturi.api.BeforeUpstreamGatewayFilter;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.core.ShortInfo;
import com.ethlo.venturi.spi.GatewayFilterFactory;

public class CorrelationIdHeaderFilterFactory implements GatewayFilterFactory<GatewayFilter, GatewayFilterFactory.EmptyConfig>
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

    private static class GF implements BeforeUpstreamGatewayFilter, BeforeCommitGatewayFilter, ShortInfo
    {
        @Override
        public void beforeUpstream(final BeforeUpstreamGatewayExchange exchange)
        {
            exchange.upstreamRequest().headers().add(X_CORRELATION_ID, exchange.requestId());
        }

        @Override
        public void beforeCommit(final BeforeCommitGatewayExchange exchange)
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