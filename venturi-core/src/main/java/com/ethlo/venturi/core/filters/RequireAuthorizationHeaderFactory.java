package com.ethlo.venturi.core.filters;

import java.nio.ByteBuffer;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayResponse;
import com.ethlo.venturi.core.ShortInfo;
import com.ethlo.venturi.spi.GatewayFilterFactory;
import com.ethlo.venturi.util.CharSequenceUtil;
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
    public GatewayFilter create(Config config)
    {
        return new GF();
    }

    // Empty record as this filter requires no configuration
    public record Config() implements ValidatableConfig
    {
        @Override
        public void validate(ValidationResult result)
        {
        }
    }

    private static class GF implements GatewayFilter, ShortInfo
    {
        private static final ByteBuffer UNAUTHORIZED_BODY = ByteBuffer.wrap("Unauthorized".getBytes());

        @Override
        public void beforeUpstream(final GatewayExchange exchange)
        {
            final CharSequence sig = exchange.request().headers().getFirst(HttpHeaders.AUTHORIZATION);

            if (sig == null || !(CharSequenceUtil.startsWith(sig, "Bearer ") || CharSequenceUtil.startsWith(sig, "Basic ")))
            {
                final GatewayResponse response = exchange.response();
                response.status(HttpStatuses.UNAUTHORIZED);
                response.headers().set(HttpHeaders.CONTENT_TYPE, MediaTypes.TEXT_PLAIN);
                response.commitResponse(UNAUTHORIZED_BODY.slice());
            }
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": Basic, Bearer";
        }
    }
}