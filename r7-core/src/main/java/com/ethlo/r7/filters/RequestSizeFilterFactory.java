package com.ethlo.r7.filters;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.config.DataSize;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.FastTerminationGatewayResponse;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.util.constants.MediaTypes;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public final class RequestSizeFilterFactory implements GatewayFilterFactory<RequestSizeFilterFactory.Config>
{
    private static final String FILTER_NAME = "RequestSize";

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
    public ClientRequestGatewayFilter create(final Config config, FilterCreationContext filterCreationContext)
    {
        return new GF(config);
    }

    public record Config(DataSize maxSize) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            if (this.maxSize() == null)
            {
                result.addError(FILTER_NAME, "max_size must be defined");
            }
        }
    }

    private static final class GF implements ClientRequestGatewayFilter, ShortInfo
    {
        private static final byte[] REJECT_PAYLOAD = "Payload Too Large".getBytes(StandardCharsets.UTF_8);
        private final DataSize maxSize;

        public GF(final Config config)
        {
            this.maxSize = config.maxSize();
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            final CharSequence contentLengthOpt = exchange.clientRequest().headers().getFirst(HttpHeaders.CONTENT_LENGTH);
            final long contentLength = contentLengthOpt != null ? Long.parseLong(contentLengthOpt.toString()) : 0;
            if (contentLength > this.maxSize.toBytes())
            {
                exchange.shortCircuit(new FastTerminationGatewayResponse(
                        HttpStatuses.ENTITY_TOO_LARGE,
                        MediaTypes.TEXT_PLAIN,
                        ByteBuffer.wrap(REJECT_PAYLOAD)
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
            return FILTER_NAME + " (" + this.maxSize + " bytes)";
        }
    }
}