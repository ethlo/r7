package com.ethlo.venturi.filters;

import java.nio.ByteBuffer;

import com.ethlo.venturi.api.BeforeUpstreamGatewayFilter;
import com.ethlo.venturi.api.UpstreamRequestGatewayExchange;
import com.ethlo.venturi.core.ShortInfo;
import com.ethlo.venturi.spi.GatewayFilterFactory;
import com.ethlo.venturi.util.CharSequenceUtil;
import com.ethlo.venturi.util.FastTerminationGatewayResponse;
import com.ethlo.venturi.util.constants.HttpHeaders;
import com.ethlo.venturi.util.constants.HttpStatuses;
import com.ethlo.venturi.util.constants.MediaTypes;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;

public class RequireAuthorizationHeaderFactory implements GatewayFilterFactory<RequireAuthorizationHeaderFactory.Config>
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
    public BeforeUpstreamGatewayFilter create(Config config)
    {
        return new GF();
    }

    public record Config() implements ValidatableConfig
    {
        @Override
        public void validate(ValidationResult result)
        {
        }
    }

    private static class GF implements BeforeUpstreamGatewayFilter, ShortInfo
    {
        private static final ByteBuffer UNAUTHORIZED_BODY = ByteBuffer.wrap("Unauthorized".getBytes());

        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            final CharSequence sig = exchange.clientRequest().headers().getFirst(HttpHeaders.AUTHORIZATION);

            if (sig == null || !(CharSequenceUtil.startsWith(sig, "Bearer ") || CharSequenceUtil.startsWith(sig, "Basic ")))
            {
                exchange.terminate(new FastTerminationGatewayResponse(HttpStatuses.UNAUTHORIZED, MediaTypes.TEXT_PLAIN, UNAUTHORIZED_BODY.slice()));
            }
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": Basic, Bearer";
        }
    }
}