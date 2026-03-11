package com.ethlo.r7.filters;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.core.ShortInfo;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.FastTerminationGatewayResponse;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public final class CorsFilterFactory implements GatewayFilterFactory<CorsFilterFactory.Config>
{
    private static final String FILTER_NAME = "Cors";

    private static final String ORIGIN = "Origin";
    private static final String ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String ACCESS_CONTROL_ALLOW_METHODS = "Access-Control-Allow-Methods";
    private static final String ACCESS_CONTROL_ALLOW_HEADERS = "Access-Control-Allow-Headers";
    private static final String ACCESS_CONTROL_MAX_AGE = "Access-Control-Max-Age";
    private static final String ACCESS_CONTROL_ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";

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
    public ClientRequestGatewayFilter create(final Config config)
    {
        return new GF(config);
    }

    public record Config(String allowedOrigins, String allowedMethods, String allowedHeaders, String maxAge,
                         Boolean allowCredentials) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result).required(FILTER_NAME, "allowedOrigins", this.allowedOrigins());
        }
    }

    private static final class GF implements ClientRequestGatewayFilter, ClientResponseGatewayFilter, ShortInfo
    {
        private static final ByteBuffer EMPTY_BODY = ByteBuffer.allocateDirect(0);

        private final String allowedMethods;
        private final String allowedHeaders;
        private final String maxAge;
        private final boolean allowCredentials;
        private final boolean isAnyOrigin;
        private final List<String> specificOrigins;

        public GF(final Config config)
        {
            this.allowedMethods = config.allowedMethods();
            this.allowedHeaders = config.allowedHeaders();
            this.maxAge = config.maxAge();
            this.allowCredentials = config.allowCredentials() != null && config.allowCredentials();

            final String originsConfig = config.allowedOrigins();
            this.isAnyOrigin = "*".equals(originsConfig);

            if (!this.isAnyOrigin && originsConfig != null)
            {
                this.specificOrigins = Arrays.asList(originsConfig.split("\\s*,\\s*"));
            }
            else
            {
                this.specificOrigins = List.of();
            }
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            final String method = exchange.clientRequest().method().toString();

            // Intercept Preflight OPTIONS requests
            if ("OPTIONS".equals(method))
            {
                final CharSequence originOpt = exchange.clientRequest().headers().getFirst(ORIGIN);
                final String origin = originOpt != null ? originOpt.toString() : null;
                if (origin != null)
                {
                    final MutableGatewayHeaders headers = new MutableFastGatewayHeaders(5);
                    if (this.isAnyOrigin)
                    {
                        headers.set(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
                    }
                    else if (this.specificOrigins.contains(origin))
                    {
                        headers.set(ACCESS_CONTROL_ALLOW_ORIGIN, origin);
                    }

                    if (this.allowedMethods != null)
                    {
                        headers.set(ACCESS_CONTROL_ALLOW_METHODS, this.allowedMethods);
                    }
                    if (this.allowedHeaders != null)
                    {
                        headers.set(ACCESS_CONTROL_ALLOW_HEADERS, this.allowedHeaders);
                    }
                    if (this.maxAge != null)
                    {
                        headers.set(ACCESS_CONTROL_MAX_AGE, this.maxAge);
                    }
                    if (this.allowCredentials)
                    {
                        headers.set(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
                    }

                    // Short-circuit before routing to upstream
                    exchange.shortCircuit(new FastTerminationGatewayResponse(
                            headers,
                            HttpStatuses.NO_CONTENT,
                            EMPTY_BODY.slice()
                    ));
                }
            }
        }

        @Override
        public void onClientResponse(final ClientResponseGatewayExchange exchange)
        {
            // Decorate standard requests with the resulting CORS headers
            final CharSequence originOpt = exchange.clientRequest().headers().getFirst(ORIGIN);
            final String origin = originOpt != null ? originOpt.toString() : null;

            if (origin != null)
            {
                final MutableGatewayHeaders responseHeaders = exchange.clientResponse().headers();

                if (this.isAnyOrigin)
                {
                    responseHeaders.set(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
                }
                else if (this.specificOrigins.contains(origin))
                {
                    responseHeaders.set(ACCESS_CONTROL_ALLOW_ORIGIN, origin);
                }

                if (this.allowCredentials)
                {
                    responseHeaders.set(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
                }
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
            return FILTER_NAME + " (Origins: " + (this.isAnyOrigin ? "*" : String.join(", ", this.specificOrigins)) + ")";
        }
    }
}