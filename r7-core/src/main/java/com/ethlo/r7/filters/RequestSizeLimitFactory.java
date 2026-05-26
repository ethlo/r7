package com.ethlo.r7.filters;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.config.model.DataSize;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.FastTerminationGatewayResponse;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.util.constants.MediaTypes;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
@Description("Rejects requests that exceed a specified payload size.")
public final class RequestSizeLimitFactory implements GatewayFilterFactory<RequestSizeLimitFactory.Config>
{
    private static final String FILTER_NAME = "RequestSizeLimit";

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
            @Description("The maximum allowed payload size (e.g., 10MB, 1GB).")
            DataSize maxSize) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result).requirePositive("max_size", this.maxSize());
        }
    }

    private static final class GF implements ClientRequestGatewayFilter, ShortInfo
    {
        private static final byte[] REJECT_PAYLOAD = "Payload Too Large".getBytes(UTF_8);
        private static final byte[] BAD_REQUEST_PAYLOAD = "Bad Request: Malformed Content-Length".getBytes(UTF_8);

        private final DataSize maxSize;

        public GF(final Config config)
        {
            this.maxSize = config.maxSize();
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            final String contentLengthHeader = exchange.clientRequest().headers().getFirst(HttpHeaders.CONTENT_LENGTH);

            if (contentLengthHeader == null)
            {
                return;
            }

            final long contentLength;
            try
            {
                contentLength = Long.parseLong(contentLengthHeader.trim());
                if (contentLength < 0)
                {
                    this.rejectBadRequest(exchange);
                    return;
                }
            }
            catch (final NumberFormatException exception)
            {
                this.rejectBadRequest(exchange);
                return;
            }

            if (contentLength > this.maxSize.bytes())
            {
                this.rejectPayloadTooLarge(exchange);
            }
        }

        private void rejectBadRequest(final ClientRequestGatewayExchange exchange)
        {
            exchange.shortCircuit(new FastTerminationGatewayResponse(
                    HttpStatuses.BAD_REQUEST,
                    MediaTypes.TEXT_PLAIN,
                    ByteBuffer.wrap(BAD_REQUEST_PAYLOAD)
            ));
        }

        private void rejectPayloadTooLarge(final ClientRequestGatewayExchange exchange)
        {
            exchange.shortCircuit(new FastTerminationGatewayResponse(
                    HttpStatuses.ENTITY_TOO_LARGE,
                    MediaTypes.TEXT_PLAIN,
                    ByteBuffer.wrap(REJECT_PAYLOAD)
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
            return FILTER_NAME + " (" + this.maxSize + " bytes)";
        }
    }
}