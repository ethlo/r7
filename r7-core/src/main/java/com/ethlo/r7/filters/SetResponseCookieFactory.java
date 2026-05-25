package com.ethlo.r7.filters;

import java.time.Duration;

import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.doc.Nullable;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
@Description("Sets a Set-Cookie header on the client response.")
public final class SetResponseCookieFactory implements GatewayFilterFactory<SetResponseCookieFactory.Config>
{
    private static final String FILTER_NAME = "SetResponseCookie";

    @Override
    public String name()
    {
        return FILTER_NAME;
    }

    @Override
    public Class<Config> configClass()
    {
        return Config.class;
    }

    @Override
    public ClientResponseGatewayFilter create(final Config config, final FilterCreationContext filterCreationContext)
    {
        return new GF(config);
    }

    public record Config(
            @Description("The cookie name.")
            String name,
            @Description("The cookie value.")
            String value,
            @Nullable @Description("The cookie domain.")
            String domain,
            @Nullable @Description("The cookie path.")
            String path,
            @Nullable @Description("The cookie max-age duration.")
            Duration maxAge,
            @Nullable @Description("Whether the cookie is secure.")
            Boolean secure,
            @Nullable @Description("Whether the cookie is HttpOnly.")
            Boolean httpOnly,
            @Nullable @Description("The SameSite policy (Strict, Lax, None).")
            String sameSite
    ) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validator = new ValidatorUtils(result)
                    .required("name", this.name())
                    .required("value", this.value());

            if (this.sameSite() != null)
            {
                if (!this.sameSite().equalsIgnoreCase("Strict") &&
                        !this.sameSite().equalsIgnoreCase("Lax") &&
                        !this.sameSite().equalsIgnoreCase("None"))
                {
                    validator.invalid("sameSite", this.sameSite(), "Must be Strict, Lax, or None");
                }
            }
        }
    }

    private static final class GF implements ClientResponseGatewayFilter, ShortInfo
    {
        private final String cookieString;

        public GF(final Config config)
        {
            this.cookieString = buildSetCookieString(config);
        }

        private static String buildSetCookieString(final Config config)
        {
            final StringBuilder builder = new StringBuilder();
            builder.append(config.name()).append("=").append(config.value());

            if (config.domain() != null)
            {
                builder.append("; Domain=").append(config.domain());
            }
            if (config.path() != null)
            {
                builder.append("; Path=").append(config.path());
            }
            if (config.maxAge() != null)
            {
                builder.append("; Max-Age=").append(config.maxAge().toSeconds());
            }
            if (config.secure() != null && config.secure())
            {
                builder.append("; Secure");
            }
            if (config.httpOnly() != null && config.httpOnly())
            {
                builder.append("; HttpOnly");
            }
            if (config.sameSite() != null)
            {
                builder.append("; SameSite=").append(config.sameSite());
            }

            return builder.toString();
        }

        @Override
        public void onClientResponse(final ClientResponseGatewayExchange exchange)
        {
            // We still use add() on the HTTP headers object because multiple Set-Cookie 
            // headers are valid in a single HTTP response (one for each cookie).
            exchange.clientResponse().headers().add(HttpHeaders.SET_COOKIE, this.cookieString);
        }

        @Override
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + this.cookieString;
        }
    }
}