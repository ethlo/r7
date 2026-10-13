package com.ethlo.r7.predicates;

import java.util.regex.Pattern;

import com.ethlo.r7.api.GatewayResponse;
import com.ethlo.r7.api.GatewayResponsePredicate;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.spi.GatewayResponsePredicateFactory;
import com.ethlo.r7.util.RepeatedValues;
import com.ethlo.r7.util.SensitiveConfig;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayResponsePredicateFactory.class)
@Description("Matches if a response header exists and its value matches the provided regular expression.")
public final class MatchResponseHeaderFactory implements GatewayResponsePredicateFactory<MatchResponseHeaderFactory.Config>
{
    private static final String PREDICATE_NAME = "MatchResponseHeader";

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
    public GatewayResponsePredicate create(final Config config)
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

    private static final class GP implements GatewayResponsePredicate
    {
        private final String headerName;
        private final Pattern pattern;

        GP(final Config config)
        {
            this.headerName = config.name();
            // Validation guarantees the syntax is correct.
            this.pattern = Pattern.compile(config.regexp());
        }

        @Override
        public boolean test(final GatewayResponse response)
        {
            // Every occurrence, not the first, and under the regex budget: see RepeatedValues.
            return RepeatedValues.allMatch(response.headers(), this.headerName, this.pattern);
        }

        @Override
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + this.headerName + " ~ " + SensitiveConfig.MASK;
        }
    }
}
