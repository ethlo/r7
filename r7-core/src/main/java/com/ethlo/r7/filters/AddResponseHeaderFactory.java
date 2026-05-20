package com.ethlo.r7.filters;

import com.ethlo.r7.RedactUtil;
import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public class AddResponseHeaderFactory implements GatewayFilterFactory<AddResponseHeaderFactory.Config>
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
    public ClientResponseGatewayFilter create(Config config, FilterCreationContext filterCreationContext)
    {
        return new GF(config);
    }

    public record Config(String name, String value, Boolean override) implements ValidatableConfig
    {
        @Override
        public void validate(ValidationResult result)
        {
            new ValidatorUtils(result)
                    .required("name", name)
                    .required("value", value);
        }
    }

    private static class GF implements ClientResponseGatewayFilter, ShortInfo
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
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + name + ": " + RedactUtil.fingerprint(value);
        }
    }
}