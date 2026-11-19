package com.ethlo.r7.filters;

import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
@Description("Removes a specific cookie from the upstream request.")
public final class RemoveRequestCookieFactory implements GatewayFilterFactory<RemoveRequestCookieFactory.Config>
{
    private static final String FILTER_NAME = "RemoveRequestCookie";

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

    public record Config(
            @Description("The name of the cookie to remove.")
            String name) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validator = new ValidatorUtils(result);
            validator.required("name", this.name());
        }
    }

    private static final class GF implements UpstreamRequestGatewayFilter, ShortInfo
    {
        private final String cookieName;

        public GF(final Config config)
        {
            this.cookieName = config.name();
        }

        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            exchange.upstreamRequest().cookies().remove(this.cookieName);
        }

        @Override
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + this.cookieName;
        }
    }
}