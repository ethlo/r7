package com.ethlo.venturi.core.filters;

import com.ethlo.venturi.api.BeforeUpstreamGatewayExchange;
import com.ethlo.venturi.api.BeforeUpstreamGatewayFilter;
import com.ethlo.venturi.api.MutableGatewayHeaders;
import com.ethlo.venturi.core.ShortInfo;
import com.ethlo.venturi.spi.GatewayFilterFactory;
import com.ethlo.venturi.util.constants.HttpHeaders;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;

public class StripCacheHeadersFactory implements GatewayFilterFactory<BeforeUpstreamGatewayFilter, StripCacheHeadersFactory.Config>
{
    private static final String FILTER_NAME = "StripCacheHeaders";

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
    public BeforeUpstreamGatewayFilter create(Config config)
    {
        return new GF();
    }

    // Empty record, no config args required
    public record Config() implements ValidatableConfig
    {
        @Override
        public void validate(ValidationResult result)
        {
        }
    }

    private static class GF implements BeforeUpstreamGatewayFilter, ShortInfo
    {
        @Override
        public void beforeUpstream(final BeforeUpstreamGatewayExchange exchange)
        {
            final MutableGatewayHeaders headers = exchange.upstreamRequest().headers();
            headers.remove(HttpHeaders.IF_MODIFIED_SINCE);
            headers.remove(HttpHeaders.IF_NONE_MATCH);
            headers.set(HttpHeaders.CACHE_CONTROL, "no-cache");
            headers.set(HttpHeaders.PRAGMA, "no-cache");
        }

        @Override
        public String summary()
        {
            return FILTER_NAME;
        }
    }
}