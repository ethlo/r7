package com.ethlo.r7.filters;

import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.core.SimpleCookie;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public class AddRequestCookieFactory implements GatewayFilterFactory<AddRequestCookieFactory.Config>
{
    private static final String FILTER_NAME = "AddRequestCookie";

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
        private final SimpleCookie cookie;

        public GF(final Config config)
        {
            // Instantiate the cookie once during configuration loading, not per-request
            this.cookie = new SimpleCookie(config.name(), config.value());
        }

        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            exchange.upstreamRequest().cookies().set(this.cookie);
        }

        @Override
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + this.cookie.name() + "=" + this.cookie.value();
        }
    }
}