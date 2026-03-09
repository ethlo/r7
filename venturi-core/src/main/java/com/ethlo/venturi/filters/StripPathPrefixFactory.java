package com.ethlo.venturi.filters;

import com.ethlo.venturi.api.UpstreamRequestGatewayFilter;
import com.ethlo.venturi.api.UpstreamRequestGatewayExchange;
import com.ethlo.venturi.core.ShortInfo;
import com.ethlo.venturi.spi.GatewayFilterFactory;
import com.ethlo.venturi.util.ValidatorUtils;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;

public class StripPathPrefixFactory implements GatewayFilterFactory<StripPathPrefixFactory.Config>
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
    public UpstreamRequestGatewayFilter create(Config config)
    {
        return new GF(config);
    }

    public record Config(Integer parts) implements ValidatableConfig
    {
        @Override
        public void validate(ValidationResult result)
        {
            final ValidatorUtils validatorUtils = new ValidatorUtils(result)
                    .required(FILTER_NAME, "parts", parts);

            if (parts != null && parts <= 0)
            {
                validatorUtils.invalid(FILTER_NAME, "parts", parts.toString(), "'parts' must be greater than 0");
            }
        }
    }

    private static class GF implements UpstreamRequestGatewayFilter, ShortInfo
    {
        private final int parts;

        public GF(Config config)
        {
            this.parts = config.parts();
        }

        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            final String path = exchange.clientRequest().path().toString();

            // The config validation guarantees parts > 0, so we can skip that check here
            int pos = 0;
            for (int i = 0; i < parts; i++)
            {
                // Micro-optimization: Search for the char '/' instead of the String "/"
                pos = path.indexOf('/', pos + 1);
                if (pos == -1)
                {
                    // We've run out of slashes.
                    // If we're at "/v1" and stripping 1, we should result in "/"
                    exchange.upstreamRequest().path("/");
                    return;
                }
            }

            final String newPath = path.substring(pos);
            // Ensure we don't return an empty string if the path ended exactly at the slash
            exchange.upstreamRequest().path(newPath.isEmpty() ? "/" : newPath);
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + (parts == 1 ? "1 part" : parts + " parts");
        }
    }
}