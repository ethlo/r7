package com.ethlo.r7.filters;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.config.model.HttpStatus;
import com.ethlo.r7.doc.DefaultValue;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.FastTerminationGatewayResponse;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.util.constants.MediaTypes;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
@Description("Ensures a required query parameter exists and matches a specific regular expression.")
public final class RequireMatchQueryParameterFactory implements GatewayFilterFactory<RequireMatchQueryParameterFactory.Config>
{
    private static final String FILTER_NAME = "RequireMatchQueryParameter";

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
            @Description("The name of the query parameter.")
            String name,

            @Description("The regular expression pattern the query parameter value must match.")
            String regexp,

            @Description("The HTTP status code to return if the parameter is missing or invalid.")
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
            new ValidatorUtils(result)
                    .notBlank("name", this.name())
                    .requiredRegexp("regexp", this.regexp());
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

            final String msg = "Invalid format for query parameter: " + config.name();
            this.errorBody = ByteBuffer.wrap(msg.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            final String paramValue = exchange.clientRequest().queryParams().getFirst(this.config.name());

            if (paramValue == null || !this.compiledPattern.matcher(paramValue).matches())
            {
                exchange.shortCircuit(new FastTerminationGatewayResponse(
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