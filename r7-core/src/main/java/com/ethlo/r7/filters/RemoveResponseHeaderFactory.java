package com.ethlo.r7.filters;

import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.core.ShortInfo;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public final class RemoveResponseHeaderFactory implements GatewayFilterFactory<RemoveResponseHeaderFactory.Config>
{
    private static final String FILTER_NAME = "RemoveResponseHeader";

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
    public ClientResponseGatewayFilter create(final Config config)
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

    private static final class GF implements ClientResponseGatewayFilter, ShortInfo
    {
        private final String name;

        public GF(final Config config)
        {
            this.name = config.name();
        }

        @Override
        public void onClientResponse(final ClientResponseGatewayExchange exchange)
        {
            exchange.clientResponse().headers().remove(this.name);
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