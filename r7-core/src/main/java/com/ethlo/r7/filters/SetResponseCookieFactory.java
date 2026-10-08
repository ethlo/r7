package com.ethlo.r7.filters;

import java.time.Duration;
import java.util.Optional;

import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.doc.DefaultValue;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.doc.Nullable;
import com.ethlo.r7.doc.Sensitive;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.SensitiveConfig;
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
            @Sensitive
            String value,
            @Nullable @Description("The cookie domain.")
            String domain,
            @Nullable @Description("The cookie path.")
            String path,
            @Nullable @Description("The cookie max-age duration.")
            Duration maxAge,
            @Nullable @DefaultValue("true") @Description("Whether the cookie is sent over HTTPS only. Defaults to true.")
            Boolean secure,
            @Nullable @DefaultValue("true") @Description("Whether client-side script is denied access to the cookie. Defaults to true.")
            Boolean httpOnly,
            @Nullable @DefaultValue("Lax") @Description("The SameSite policy (Strict, Lax, None). Defaults to Lax.")
            String sameSite
    ) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validator = new ValidatorUtils(result)
                    .required("name", this.name())
                    .required("value", this.value());

            // Each attribute is concatenated raw into the Set-Cookie header value; a control
            // character (CR/LF) would split the response, and a ';' would inject an extra
            // cookie attribute the operator did not intend. The cookie name is additionally a
            // token (RFC 6265 §4.1.1), so it cannot contain '=' or whitespace either.
            // safeHeaderText permits tab (it is valid in a folded header value), but RFC 6265
            // cookie-octet excludes all whitespace, so it is rejected here explicitly.
            validator.httpToken("name", this.name())
                    .safeHeaderText("value", this.value())
                    .safeHeaderText("domain", this.domain())
                    .safeHeaderText("path", this.path())
                    .safeHeaderText("sameSite", this.sameSite())
                    .excludesChar("value", this.value(), ';', "cookie value cannot contain ';'")
                    .excludesChar("domain", this.domain(), ';', "cookie domain cannot contain ';'")
                    .excludesChar("path", this.path(), ';', "cookie path cannot contain ';'")
                    .excludesChar("value", this.value(), '\t', "cookie value cannot contain a tab character")
                    .excludesChar("domain", this.domain(), '\t', "cookie domain cannot contain a tab character")
                    .excludesChar("path", this.path(), '\t', "cookie path cannot contain a tab character");

            if (!this.sameSite().equalsIgnoreCase("Strict") &&
                    !this.sameSite().equalsIgnoreCase("Lax") &&
                    !this.sameSite().equalsIgnoreCase("None"))
            {
                validator.invalid("sameSite", this.sameSite(), "Must be Strict, Lax, or None");
            }
            // Browsers drop a SameSite=None cookie that is not also Secure, so the operator would
            // get no cookie at all rather than a cross-site one.
            else if (this.sameSite().equalsIgnoreCase("None") && !this.secure())
            {
                validator.invalid("sameSite", this.sameSite(), "SameSite=None requires secure: true");
            }
        }

        // Safe unless opted out: a cookie set by the gateway is typically a token, and each
        // attribute is what keeps it off plaintext connections, away from script and out of
        // cross-site requests. Behind TLS termination the browser sees HTTPS, so Secure holds.
        @Override
        public Boolean secure()
        {
            return Optional.ofNullable(this.secure).orElse(true);
        }

        @Override
        public Boolean httpOnly()
        {
            return Optional.ofNullable(this.httpOnly).orElse(true);
        }

        @Override
        public String sameSite()
        {
            return Optional.ofNullable(this.sameSite).orElse("Lax");
        }
    }

    private static final class GF implements ClientResponseGatewayFilter, ShortInfo
    {
        private final String cookieString;
        private final String summary;

        public GF(final Config config)
        {
            this.cookieString = buildSetCookieString(config);
            // A cookie set on every response is typically a token; the summary is shown on the
            // management page, so the value is masked like the other value-setting filters.
            this.summary = FILTER_NAME + ": " + config.name() + "=" + SensitiveConfig.MASK;
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
            if (config.secure())
            {
                builder.append("; Secure");
            }
            if (config.httpOnly())
            {
                builder.append("; HttpOnly");
            }
            builder.append("; SameSite=").append(config.sameSite());

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
            return this.summary;
        }
    }
}