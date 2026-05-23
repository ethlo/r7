package com.ethlo.r7.predicates;

import java.util.regex.Pattern;

import com.ethlo.r7.api.Cookie;
import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayPredicateFactory.class)
public final class MatchCookieFactory implements GatewayPredicateFactory<MatchCookieFactory.Config>
{
    private static final String PREDICATE_NAME = "MatchCookie";

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

    public record Config(String name, String regexp) implements GenericMatchConfig
    {

    }

    private static final class GP implements GatewayPredicate, ShortInfo
    {
        private final String cookieName;
        private final Pattern pattern;

        public GP(final Config config)
        {
            this.cookieName = config.name();
            this.pattern = Pattern.compile(config.regexp());
        }

        @Override
        public boolean test(final GatewayRequest request)
        {
            final Cookie cookie = request.cookies().get(this.cookieName);

            if (cookie == null)
            {
                return false;
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
            return PREDICATE_NAME + ": " + this.cookieName + " ~ " + this.pattern.pattern();
        }
    }
}