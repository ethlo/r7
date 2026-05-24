package com.ethlo.r7.filters;

import java.nio.ByteBuffer;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.FastTerminationGatewayResponse;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.util.constants.MediaTypes;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
public final class RequireAuthorizationHeaderFactory implements GatewayFilterFactory<RequireAuthorizationHeaderFactory.Config>
{
    private static final String FILTER_NAME = "RequireAuthorizationHeader";

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
        return new GF();
    }

    public record Config() implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
        }
    }

    private static final class GF implements ClientRequestGatewayFilter, ShortInfo
    {
        private static final ByteBuffer UNAUTHORIZED_BODY = ByteBuffer.wrap("Unauthorized".getBytes());

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            final String sig = exchange.clientRequest().headers().getFirst(HttpHeaders.AUTHORIZATION);

            if (sig == null || !(sig.startsWith("Bearer ") || sig.startsWith("Basic ")))
            {
                exchange.shortCircuit(new FastTerminationGatewayResponse(
                        HttpStatuses.UNAUTHORIZED,
                        MediaTypes.TEXT_PLAIN,
                        UNAUTHORIZED_BODY.slice()
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
            return FILTER_NAME + ": Basic, Bearer";
        }
    }
}