package com.ethlo.venturi.filters;

import com.ethlo.venturi.RedactUtil;
import com.ethlo.venturi.api.BeforeUpstreamGatewayFilter;
import com.ethlo.venturi.api.UpstreamRequestGatewayExchange;
import com.ethlo.venturi.core.ShortInfo;
import com.ethlo.venturi.spi.GatewayFilterFactory;
import com.ethlo.venturi.util.ValidatorUtils;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;

public class AddRequestHeaderFactory implements GatewayFilterFactory<AddRequestHeaderFactory.Config>
{
    private static final String FILTER_NAME = "AddRequestHeader";

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
        return new GF(config);
    }

    public record Config(String name, String value, Boolean override) implements ValidatableConfig
    {
        @Override
        public void validate(ValidationResult result)
        {
            new ValidatorUtils(result)
                    .required(FILTER_NAME, "name", name)
                    .required(FILTER_NAME, "value", value);
        }
    }

    private static class GF implements BeforeUpstreamGatewayFilter, ShortInfo
    {
        private final boolean override;
        private final String name;
        private final String value;

        public GF(Config config)
        {
            this.override = config.override() != null && config.override();
            this.name = config.name();
            this.value = config.value();
        }

        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            if (override)
            {
                exchange.upstreamRequest().headers().set(name, value);
            }
            else
            {
                exchange.upstreamRequest().headers().add(name, value);
            }
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + name + ": " + RedactUtil.fingerprint(value);
        }
    }
}