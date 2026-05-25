package com.ethlo.r7.predicates;

import java.util.regex.Pattern;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayPredicateFactory.class)
@Description("Matches the request path against a regular expression.")
public final class MatchPathFactory implements GatewayPredicateFactory<MatchPathFactory.Config>
{
    private static final String PREDICATE_NAME = "MatchPath";

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
            @Description("The regular expression to match against the request path.")
            String regexp) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result).requiredRegexp("regexp", this.regexp());
        }
    }

    private static final class GP implements GatewayPredicate, ShortInfo
    {
        private final Pattern pattern;

        public GP(final Config config)
        {
            this.pattern = Pattern.compile(config.regexp());
        }

        @Override
        public boolean test(final GatewayRequest request)
        {
            return this.pattern.matcher(request.path()).matches();
        }

        @Override
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + this.pattern.pattern();
        }
    }
}