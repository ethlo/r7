package com.ethlo.venturi.core.filters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.core.ShortInfo;
import com.ethlo.venturi.util.ValidatorUtils;
import com.ethlo.venturi.spi.GatewayFilterFactory;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;

public class RewritePathFactory implements GatewayFilterFactory<RewritePathFactory.Config>
{
    private static final String FILTER_NAME = "RewritePath";

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
    public GatewayFilter create(Config config)
    {
        return new GF(config);
    }

    public record Config(String regexp, String replacement) implements ValidatableConfig
    {
        @Override
        public void validate(ValidationResult result)
        {
            new ValidatorUtils(result)
                    .required(FILTER_NAME, "regexp", regexp)
                    .required(FILTER_NAME, "replacement", replacement);

            if (regexp != null)
            {
                try
                {
                    Pattern.compile(regexp);
                }
                catch (PatternSyntaxException e)
                {
                    // Assuming your ValidationResult can take custom error messages
                    throw new IllegalArgumentException(FILTER_NAME + ": Invalid regex pattern - " + e.getMessage());
                }
            }
        }
    }

    private static class GF implements GatewayFilter, ShortInfo
    {
        private final Pattern regexp;
        private final String replacement;

        public GF(Config config)
        {
            this.regexp = Pattern.compile(config.regexp());
            this.replacement = config.replacement();
        }

        @Override
        public void beforeUpstream(final GatewayExchange exchange)
        {
            final CharSequence path = exchange.request().path();
            final Matcher matcher = regexp.matcher(path);

            if (matcher.find())
            {
                final String newPath = matcher.replaceAll(replacement);
                exchange.upstreamRequest().path(newPath);
            }
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + regexp.pattern() + " -> " + replacement;
        }
    }
}