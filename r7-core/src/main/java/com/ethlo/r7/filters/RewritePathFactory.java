package com.ethlo.r7.filters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
public final class RewritePathFactory implements GatewayFilterFactory<RewritePathFactory.Config>
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
    public UpstreamRequestGatewayFilter create(final Config config, final FilterCreationContext filterCreationContext)
    {
        return new GF(config);
    }

    public record Config(String regexp, String replacement) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result)
                    .requiredRegexp("regexp", this.regexp())
                    .required("replacement", this.replacement())
                    .validRegexReplacement("replacement", this.regexp(), this.replacement());
        }
    }

    private static final class GF implements UpstreamRequestGatewayFilter, ShortInfo
    {
        private final Pattern regexp;
        private final String replacement;

        public GF(final Config config)
        {
            this.regexp = Pattern.compile(config.regexp());
            this.replacement = config.replacement();
        }

        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            final Matcher matcher = this.regexp.matcher(exchange.clientRequest().path());

            if (matcher.find())
            {
                final String newPath = matcher.replaceAll(this.replacement);
                exchange.upstreamRequest().path(newPath);
            }
        }

        @Override
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + this.regexp.pattern() + " -> " + this.replacement;
        }
    }
}