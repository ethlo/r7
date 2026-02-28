package com.ethlo.venturi.core.filters;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.core.ShortInfo;
import com.ethlo.venturi.spi.GatewayFilterFactory;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;

import static com.ethlo.venturi.util.constants.HttpHeaders.X_CORRELATION_ID;

public class CorrelationIdHeaderFilterFactory implements GatewayFilterFactory<CorrelationIdHeaderFilterFactory.Config>
{
    private static final String FILTER_NAME = "CorrelationIdHeader";

    @Override
    public String name()
    {
        return FILTER_NAME;
    }

    @Override
    public Class<Config> configClass()
    {
        return Config.class;
    }

    @Override
    public GatewayFilter create(Config config)
    {
        return new GF();
    }

    public record Config() implements ValidatableConfig
    {
        @Override
        public void validate(ValidationResult result)
        {
            // Nothing to validate!
        }
    }

    private static class GF implements GatewayFilter, ShortInfo
    {
        @Override
        public void beforeUpstream(final GatewayExchange exchange)
        {
            final CharSequence requestId = exchange.requestId();
            exchange.request().headers().add(X_CORRELATION_ID, requestId);
            exchange.response().headers().add(X_CORRELATION_ID, requestId);
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + X_CORRELATION_ID;
        }
    }
}