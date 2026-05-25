package com.ethlo.r7.filters;

import com.ethlo.r7.doc.Description;
import com.ethlo.r7.util.RedactUtil;
import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
@Description("Sets (or overwrites) a header in the client response.")
public final class SetResponseHeaderFactory implements GatewayFilterFactory<SetResponseHeaderFactory.Config>
{
    private static final String FILTER_NAME = "SetResponseHeader";

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
    public ClientResponseGatewayFilter create(final Config config, final FilterCreationContext filterCreationContext)
    {
        return new GF(config);
    }

    public record Config(
            @Description("The name of the header.")
            String name,

            @Description("The value to set.")
            String value) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result)
                    .required("name", this.name())
                    .required("value", this.value());
        }
    }

    private static final class GF implements ClientResponseGatewayFilter, ShortInfo
    {
        private final String name;
        private final String value;

        public GF(final Config config)
        {
            this.name = config.name();
            this.value = config.value();
        }

        @Override
        public void onClientResponse(final ClientResponseGatewayExchange exchange)
        {
            exchange.clientResponse().headers().set(this.name, this.value);
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