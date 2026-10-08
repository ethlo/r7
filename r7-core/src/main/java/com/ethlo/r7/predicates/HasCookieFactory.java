package com.ethlo.r7.predicates;

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
@Description("Matches if a request contains the specified cookie, regardless of its value.")
public final class HasCookieFactory implements GatewayPredicateFactory<HasCookieFactory.Config>
{
    private static final String PREDICATE_NAME = "HasCookie";

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
            @Description("The name of the cookie to check for.")
            String name) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result).required("name", this.name());
        }
    }

    private static final class GP implements GatewayPredicate, ShortInfo
    {
        private final String cookieName;

        public GP(final Config config)
        {
            this.cookieName = config.name();
        }

        @Override
        public boolean test(final GatewayRequest request)
        {
            return request.cookies().get(this.cookieName) != null;
        }

        @Override
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + this.cookieName;
        }
    }
}