package com.ethlo.r7.filters;

import com.ethlo.r7.util.RedactUtil;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
public final class SetRequestHeaderFactory implements GatewayFilterFactory<SetRequestHeaderFactory.Config>
{
    private static final String FILTER_NAME = "SetRequestHeader";

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
            new ValidatorUtils(result)
                    .required("name", this.name())
                    .required("value", this.value());
        }
    }

    private static final class GF implements UpstreamRequestGatewayFilter, ShortInfo
    {
        private final String name;
        private final String value;

        public GF(final Config config)
        {
            this.name = config.name();
            this.value = config.value();
        }

        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            exchange.upstreamRequest().headers().set(this.name, this.value);
        }

        @Override
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + this.name + ": " + RedactUtil.fingerprint(this.value);
        }
    }
}