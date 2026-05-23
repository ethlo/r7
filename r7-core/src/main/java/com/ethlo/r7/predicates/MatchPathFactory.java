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
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayPredicateFactory.class)
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

    public record Config(String regexp) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validator = new ValidatorUtils(result).required("regexp", this.regexp());
            
            if (this.regexp() != null)
            {
                try
                {
                    Pattern.compile(this.regexp());
                }
                catch (final PatternSyntaxException e)
                {
                    validator.invalid("regexp", this.regexp(), "Invalid regex format: " + e.getDescription());
                }
            }
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
            // Using .path() instead of .uri() ensures query parameters do not break the regex match
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