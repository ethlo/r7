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
@Description("Matches if a query parameter exists and its value matches the provided regular expression.")
public final class MatchQueryParameterFactory implements GatewayPredicateFactory<MatchQueryParameterFactory.Config>
{
    private static final String PREDICATE_NAME = "MatchQueryParameter";

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
            @Description("The name of the query parameter.")
            String name,

            @Description("The regular expression the parameter value must match.")
            String regexp) implements GenericMatchConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result)
                    .notBlank("name", name())
                    .requiredRegexp("regexp", regexp());
        }
    }

    private static final class GP implements GatewayPredicate, ShortInfo
    {
        private final String paramName;
        private final Pattern pattern;

        public GP(final Config config)
        {
            this.paramName = config.name();
            this.pattern = Pattern.compile(config.regexp());
        }

        @Override
        public boolean test(final GatewayRequest request)
        {
            final String paramValue = request.queryParams().getFirst(this.paramName);

            if (paramValue == null)
            {
                return false;
            }

            return this.pattern.matcher(paramValue).matches();
        }

        @Override
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + this.paramName + " ~ " + this.pattern.pattern();
        }
    }
}