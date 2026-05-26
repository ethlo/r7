package com.ethlo.r7.filters;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.config.model.HttpStatus;
import com.ethlo.r7.doc.DefaultValue;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.doc.Nullable;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.FastTerminationGatewayResponse;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.util.constants.MediaTypes;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
@Description("Immediately terminates the request chain and returns a custom response.")
public final class ReturnResponseFactory implements GatewayFilterFactory<ReturnResponseFactory.Config>
{
    private static final String FILTER_NAME = "ReturnResponse";

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
            @Description("The HTTP status code to return.")
            HttpStatus status,

            @Description("The Content-Type of the response body.")
            @DefaultValue("text/plain")
            @Nullable String contentType,

            @Description("The body content of the response.")
            String body) implements ValidatableConfig
    {
        public Config
        {
            if (contentType == null)
            {
                contentType = MediaTypes.TEXT_PLAIN;
            }
        }

        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result)
                    .required("status", this.status())
                    .required("body", this.body());

            if (status != null)
            {
                status.validate(result);
            }
        }
    }

    private static final class GF implements ClientRequestGatewayFilter, ShortInfo
    {
        private final int status;
        private final String contentType;
        private final ByteBuffer bodyBuffer;

        public GF(final Config config)
        {
            this.status = config.status().code();
            this.contentType = config.contentType();
            this.bodyBuffer = ByteBuffer.wrap(config.body().getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            exchange.shortCircuit(new FastTerminationGatewayResponse(
                    this.status,
                    this.contentType,
                    this.bodyBuffer.asReadOnlyBuffer()
            ));
        }

        @Override
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + this.status + " [" + this.contentType + "]";
        }
    }
}