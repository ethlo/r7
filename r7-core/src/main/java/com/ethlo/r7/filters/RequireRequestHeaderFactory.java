package com.ethlo.r7.filters;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

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
@Description("Ensures a required header is present in the request.")
public final class RequireRequestHeaderFactory implements GatewayFilterFactory<RequireRequestHeaderFactory.Config>
{
    private static final String FILTER_NAME = "RequireRequestHeader";

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
            @Description("The name of the required header.")
            String name,

            @Description("The HTTP status code to return if the header is missing.")
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
            new ValidatorUtils(result).required("name", this.name());
        }
    }

    private static final class GF implements ClientRequestGatewayFilter, ShortInfo
    {
        private final Config config;
        private final ByteBuffer errorBody;

        public GF(final Config config)
        {
            this.config = config;
            final String msg = "Missing required header: " + config.name();
            this.errorBody = ByteBuffer.wrap(msg.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            if (exchange.clientRequest().headers().getFirst(this.config.name()) == null)
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
            return FILTER_NAME + ": " + this.config.name();
        }
    }
}