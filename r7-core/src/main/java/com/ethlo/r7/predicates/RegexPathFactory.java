package com.ethlo.r7.predicates;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public class RegexPathFactory implements GatewayPredicateFactory<RegexPathFactory.Config>
{
    private static final String PREDICATE_NAME = "RegexPath";

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
    public GatewayPredicate create(Config config)
    {
        return new GP(config);
    }

    public record Config(String regexp) implements ValidatableConfig
    {
        @Override
        public void validate(ValidationResult result)
        {
            new ValidatorUtils(result).required("regexp", regexp);
            if (regexp != null)
            {
                try
                {
                    Pattern.compile(regexp);
                }
                catch (PatternSyntaxException e)
                {
                    throw new IllegalArgumentException(PREDICATE_NAME + ": Invalid regex - " + e.getMessage());
                }
            }
        }
    }

    private static class GP implements GatewayPredicate, ShortInfo
    {
        private final Pattern pattern;

        public GP(Config config)
        {
            this.pattern = Pattern.compile(config.regexp());
        }

        @Override
        public boolean test(GatewayRequest request)
        {
            // Note: Matcher handles CharSequence natively, so no .toString() needed!
            return pattern.matcher(request.uri()).matches();
        }

        @Override
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + pattern.pattern();
        }
    }
}