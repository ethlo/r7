package com.ethlo.r7.filters;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.Cookie;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.config.model.HttpStatus;
import com.ethlo.r7.doc.DefaultValue;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ShortCircuitGatewayResponse;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.util.constants.MediaTypes;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
@Description("Ensures a required cookie exists and matches a specific regular expression.")
public final class RequireMatchCookieFactory implements GatewayFilterFactory<RequireMatchCookieFactory.Config>
{
    private static final String FILTER_NAME = "RequireMatchCookie";

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
    public ClientRequestGatewayFilter create(final Config config, final FilterCreationContext filterCreationContext)
    {
        return new GF(config);
    }

    public record Config(
            @Description("The name of the cookie.")
            String name,

            @Description("The regular expression pattern the cookie value must match.")
            String regexp,

            @Description("The HTTP status code to return if the cookie is missing or invalid.")
            @DefaultValue("400")
            HttpStatus rejectStatusCode) implements ValidatableConfig
    {
        public Config
        {
            if (rejectStatusCode == null)
            {
                rejectStatusCode = new HttpStatus(HttpStatuses.BAD_REQUEST);
            }
        }

        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validator = new ValidatorUtils(result);
            validator.required("name", this.name())
                    .requiredRegexp("regexp", regexp());
        }
    }

    private static final class GF implements ClientRequestGatewayFilter, ShortInfo
    {
        private final Config config;
        private final Pattern compiledPattern;
        private final ByteBuffer errorBody;

        public GF(final Config config)
        {
            this.config = config;
            this.compiledPattern = Pattern.compile(config.regexp());

            final String msg = "Invalid format for cookie: " + config.name();
            this.errorBody = ByteBuffer.wrap(msg.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            final Cookie cookie = exchange.clientRequest().cookies().get(this.config.name());

            if (cookie == null || cookie.value() == null || !this.compiledPattern.matcher(cookie.value()).matches())
            {
                exchange.shortCircuit(new ShortCircuitGatewayResponse(
                        this.config.rejectStatusCode().code(),
                        MediaTypes.TEXT_PLAIN,
                        this.errorBody.asReadOnlyBuffer()
                ));
            }
        }

        @Override
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + this.config.name() + " ~= " + this.config.regexp();
        }
    }
}