package com.ethlo.r7.filters;

import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public final class SetStatusFactory implements GatewayFilterFactory<SetStatusFactory.Config>
{
    private static final String FILTER_NAME = "SetStatus";

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

    public record Config(Integer status) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validatorUtils = new ValidatorUtils(result)
                    .required(FILTER_NAME, "status", this.status());

            if (this.status() != null && (this.status() < 100 || this.status() > 599))
            {
                validatorUtils.invalid(FILTER_NAME, "status", this.status().toString(), "Must be a valid HTTP status code (100-599)");
            }
        }
    }

    private static final class GF implements ClientResponseGatewayFilter, ShortInfo
    {
        private final int status;

        public GF(final Config config)
        {
            this.status = config.status();
        }

        @Override
        public void onClientResponse(final ClientResponseGatewayExchange exchange)
        {
            exchange.clientResponse().status(this.status);
        }

        @Override
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + this.status;
        }
    }
}