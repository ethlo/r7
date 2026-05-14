package com.ethlo.r7.filters;

import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public final class RemoveRequestHeaderFactory implements GatewayFilterFactory<RemoveRequestHeaderFactory.Config>
{
    private static final String FILTER_NAME = "RemoveRequestHeader";

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
    public UpstreamRequestGatewayFilter create(final Config config, FilterCreationContext filterCreationContext)
    {
        return new GF(config);
    }

    public record Config(String name) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result).required(FILTER_NAME, "name", this.name());
        }
    }

    private static final class GF implements UpstreamRequestGatewayFilter, ShortInfo
    {
        private final String name;

        public GF(final Config config)
        {
            this.name = config.name();
        }

        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            exchange.upstreamRequest().headers().remove(this.name);
        }

        @Override
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + this.name;
        }
    }
}