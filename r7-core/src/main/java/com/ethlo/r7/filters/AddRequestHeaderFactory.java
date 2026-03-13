package com.ethlo.r7.filters;

import com.ethlo.r7.RedactUtil;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

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
    public UpstreamRequestGatewayFilter create(Config config)
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

    private static class GF implements UpstreamRequestGatewayFilter, ShortInfo
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