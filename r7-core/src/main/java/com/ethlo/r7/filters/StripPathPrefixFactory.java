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
@Description("Removes a specified number of leading path segments from the request path before forwarding to the upstream service.")
public final class StripPathPrefixFactory implements GatewayFilterFactory<StripPathPrefixFactory.Config>
{
    private static final String FILTER_NAME = "StripPathPrefix";

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

    public record Config(@Description("The number of path segments to strip from the beginning of the path.")
                         Integer parts) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validatorUtils = new ValidatorUtils(result)
                    .required("parts", this.parts());

            if (this.parts() != null && this.parts() <= 0)
            {
                validatorUtils.invalid("parts", this.parts().toString(), "'parts' must be greater than 0");
            }
        }
    }

    private static final class GF implements UpstreamRequestGatewayFilter, ShortInfo
    {
        private final int parts;

        public GF(final Config config)
        {
            this.parts = config.parts();
        }

        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            final String path = exchange.clientRequest().path().toString();

            int pos = 0;
            for (int i = 0; i < this.parts; i++)
            {
                pos = path.indexOf('/', pos + 1);
                if (pos == -1)
                {
                    exchange.upstreamRequest().path("/");
                    return;
                }
            }

            final String newPath = path.substring(pos);
            exchange.upstreamRequest().path(newPath.isEmpty() ? "/" : newPath);
        }

        @Override
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + (this.parts == 1 ? "1 part" : this.parts + " parts");
        }
    }
}