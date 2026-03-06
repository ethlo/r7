package com.ethlo.venturi.core.filters;

import com.ethlo.venturi.RedactUtil;
import com.ethlo.venturi.api.ClientResponseGatewayExchange;
import com.ethlo.venturi.api.BeforeCommitGatewayFilter;
import com.ethlo.venturi.core.ShortInfo;
import com.ethlo.venturi.spi.GatewayFilterFactory;
import com.ethlo.venturi.util.ValidatorUtils;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;

public class AddResponseHeaderFactory implements GatewayFilterFactory<BeforeCommitGatewayFilter, AddResponseHeaderFactory.Config>
{
    private static final String FILTER_NAME = "AddResponseHeader";

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
    public BeforeCommitGatewayFilter create(Config config)
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

    private static class GF implements BeforeCommitGatewayFilter, ShortInfo
    {
        private final boolean override;
        private final String name;
        private final String value;

        public GF(Config config)
        {
            override = config.override() != null && config.override();
            name = config.name();
            value = config.value();
        }

        @Override
        public void onClientResponse(final ClientResponseGatewayExchange exchange)
        {
            if (override)
            {
                exchange.clientResponse().headers().set(name, value);
            }
            else
            {
                exchange.clientResponse().headers().add(name, value);
            }
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + name + ": " + RedactUtil.fingerprint(value);
        }
    }
}