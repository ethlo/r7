package com.ethlo.r7.filters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.core.ShortInfo;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

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
    public UpstreamRequestGatewayFilter create(Config config)
    {
        return new GF(config);
    }

    public record Config(String regexp, String replacement) implements ValidatableConfig
    {
        @Override
        public void validate(ValidationResult result)
        {
            final ValidatorUtils validatorUtils = new ValidatorUtils(result)
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
                    validatorUtils.invalid(FILTER_NAME, "regexp", regexp, "Invalid regex pattern: " + e.getPattern());
                }
            }
        }
    }

    private static class GF implements UpstreamRequestGatewayFilter, ShortInfo
    {
        private final Pattern regexp;
        private final String replacement;

        public GF(Config config)
        {
            this.regexp = Pattern.compile(config.regexp());
            this.replacement = config.replacement();
        }

        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            final Matcher matcher = regexp.matcher(exchange.clientRequest().path());

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