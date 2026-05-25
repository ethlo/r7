package com.ethlo.r7.filters;

import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
@Description("Removes standard cache-control headers from the upstream request to force fresh fetches.")
public final class RemoveCacheHeadersFactory implements GatewayFilterFactory<RemoveCacheHeadersFactory.Config>
{
    private static final String FILTER_NAME = "RemoveCacheHeaders";

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
    public UpstreamRequestGatewayFilter create(final Config config, final FilterCreationContext filterCreationContext)
    {
        return new GF();
    }

    public record Config() implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
        }
    }

    private static final class GF implements UpstreamRequestGatewayFilter, ShortInfo
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
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return FILTER_NAME;
        }
    }
}