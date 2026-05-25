package com.ethlo.r7.predicates;

import java.util.regex.Pattern;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayPredicateFactory.class)
@Description("Matches if a request header exists and its value matches the provided regular expression.")
public final class MatchRequestHeaderFactory implements GatewayPredicateFactory<MatchRequestHeaderFactory.Config>
{
    private static final String PREDICATE_NAME = "MatchRequestHeader";

    @Override
    public String name()
    {
        return PREDICATE_NAME;
    }

    @Override
    public Class<Config> configClass()
    {
        return Config.class;
    }

    @Override
    public GatewayPredicate create(final Config config)
    {
        return new GP(config);
    }

    public record Config(
            @Description("The name of the header.")
            String name,

            @Description("The regular expression the header value must match.")
            String regexp) implements GenericMatchConfig
    {

    }

    private static final class GP implements GatewayPredicate, ShortInfo
    {
        private final String headerName;
        private final Pattern pattern;

        public GP(final Config config)
        {
            this.headerName = config.name();
            // It is safe to compile here because validation guarantees the syntax is correct
            this.pattern = Pattern.compile(config.regexp());
        }

        @Override
        public boolean test(final GatewayRequest request)
        {
            final String headerValue = request.headers().getFirst(this.headerName);

            if (headerValue == null)
            {
                return false;
            }

            return this.pattern.matcher(headerValue).matches();
        }

        @Override
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + this.headerName + " ~ " + this.pattern.pattern();
        }
    }
}