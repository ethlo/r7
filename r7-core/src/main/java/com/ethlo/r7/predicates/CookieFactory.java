package com.ethlo.r7.predicates;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.ethlo.r7.api.Cookie;
import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public final class CookieFactory implements GatewayPredicateFactory<CookieFactory.Config>
{
    private static final String PREDICATE_NAME = "Cookie";

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

    public record Config(String name, String regexp) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validator = new ValidatorUtils(result).required("name", this.name());

            if (this.regexp() != null)
            {
                try
                {
                    Pattern.compile(this.regexp());
                }
                catch (final PatternSyntaxException e)
                {
                    validator.invalid("regexp", this.regexp(), "Invalid regex format");
                }
            }
        }
    }

    private static final class GP implements GatewayPredicate, ShortInfo
    {
        private final String cookieName;
        private final Pattern pattern;

        public GP(final Config config)
        {
            this.cookieName = config.name();
            this.pattern = config.regexp() != null ? Pattern.compile(config.regexp()) : null;
        }

        @Override
        public boolean test(final GatewayRequest request)
        {
            final Cookie cookie = request.cookies().get(this.cookieName);

            if (cookie == null)
            {
                return false;
            }

            if (this.pattern == null)
            {
                return true;
            }

            return this.pattern.matcher(cookie.value()).matches();
        }

        @Override
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            if (this.pattern == null)
            {
                return PREDICATE_NAME + ": " + this.cookieName;
            }
            else
            {
                return PREDICATE_NAME + ": " + this.cookieName + " ~ " + this.pattern.pattern();
            }
        }
    }
}