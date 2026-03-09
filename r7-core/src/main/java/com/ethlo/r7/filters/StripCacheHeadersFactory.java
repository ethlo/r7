package com.ethlo.r7.filters;

import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.core.ShortInfo;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

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
    public UpstreamRequestGatewayFilter create(Config config)
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

    private static class GF implements UpstreamRequestGatewayFilter, ShortInfo
    {
        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
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