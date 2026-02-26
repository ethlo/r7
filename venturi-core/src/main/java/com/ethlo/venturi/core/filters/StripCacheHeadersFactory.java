package com.ethlo.venturi.core.filters;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.core.ShortInfo;
import com.ethlo.venturi.spi.GatewayFilterFactory;
import com.ethlo.venturi.util.constants.HttpHeaders;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;

public class StripCacheHeadersFactory implements GatewayFilterFactory<StripCacheHeadersFactory.Config>
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
    public GatewayFilter create(Config config)
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

    private static class GF implements GatewayFilter, ShortInfo
    {
        @Override
        public void beforeUpstream(final GatewayExchange exchange)
        {
            exchange.request().headers().remove(HttpHeaders.IF_MODIFIED_SINCE);
            exchange.request().headers().remove(HttpHeaders.IF_NONE_MATCH);

            exchange.request().headers().set(HttpHeaders.CACHE_CONTROL, "no-cache");
            exchange.request().headers().set(HttpHeaders.PRAGMA, "no-cache");
        }

        @Override
        public String summary()
        {
            return FILTER_NAME;
        }
    }
}