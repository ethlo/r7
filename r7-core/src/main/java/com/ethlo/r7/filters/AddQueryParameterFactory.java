package com.ethlo.r7.filters;

import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public class AddQueryParameterFactory implements GatewayFilterFactory<AddQueryParameterFactory.Config>
{
    private static final String FILTER_NAME = "AddQueryParameter";

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
        return new GF(config);
    }

    public record Config(String name, String value) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validator = new ValidatorUtils(result);
            validator.required("name", this.name);
            validator.required("value", this.value);
        }
    }

    private static class GF implements UpstreamRequestGatewayFilter, ShortInfo
    {
        private final String paramName;
        private final String paramValue;

        public GF(final Config config)
        {
            this.paramName = config.name();
            this.paramValue = config.value();
        }

        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            exchange.upstreamRequest().queryParams().add(this.paramName, this.paramValue);
        }

        @Override
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + this.paramName + "=" + this.paramValue;
        }
    }
}