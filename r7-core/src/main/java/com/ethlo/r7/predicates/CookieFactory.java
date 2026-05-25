package com.ethlo.r7.predicates;

import com.ethlo.r7.api.Cookie;
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
@Description("Matches if a request contains a specific cookie with an exact value.")
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

    public record Config(
            @Description("The name of the cookie.")
            String name,

            @Description("The exact value the cookie must hold.")
            String value) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result)
                    .required("name", this.name())
                    .required("value", this.value());
        }
    }

    private static final class GP implements GatewayPredicate, ShortInfo
    {
        private final String cookieName;
        private final String targetValue;

        public GP(final Config config)
        {
            this.cookieName = config.name();
            this.targetValue = config.value();
        }

        @Override
        public boolean test(final GatewayRequest request)
        {
            final Cookie cookie = request.cookies().get(this.cookieName);

            if (cookie == null)
            {
                return false;
            }

            return this.targetValue.equals(cookie.value());
        }

        @Override
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + this.cookieName + " == " + this.targetValue;
        }
    }
}