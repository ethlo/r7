package com.ethlo.r7.filters;

import java.nio.ByteBuffer;
import java.util.Set;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.doc.DefaultValue;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ShortCircuitGatewayResponse;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
@Description("Handles Cross-Origin Resource Sharing (CORS) preflight and response headers.")
public final class CorsFactory implements GatewayFilterFactory<CorsFactory.Config>
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
    public ClientRequestGatewayFilter create(final Config config, final FilterCreationContext filterCreationContext)
    {
        return new GF(config);
    }

    public record Config(
            @Description("List of allowed origins.")
            Set<String> allowedOrigins,

            @Description("List of allowed methods.")
            Set<String> allowedMethods,

            @Description("List of allowed headers.")
            Set<String> allowedHeaders,

            @DefaultValue("30m")
            @Description("The max age for preflight caching. Defaults to 1800 seconds (30 minutes).")
            String maxAge,

            @DefaultValue("false")
            @Description("Whether credentials are allowed. Defaults to false.")
            Boolean allowCredentials) implements ValidatableConfig
    {
        // Compact constructor to assign secure defaults
        public Config
        {
            if (maxAge == null || maxAge.isBlank())
            {
                maxAge = "1800";
            }
            if (allowCredentials == null)
            {
                allowCredentials = false;
            }
        }

        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validator = new ValidatorUtils(result);

            validator.required("allowed_origins", this.allowedOrigins());
            validator.required("allowed_methods", this.allowedMethods());

            // Enforce CORS specification: Cannot use wildcards with credentials
            if (Boolean.TRUE.equals(this.allowCredentials()) && this.allowedOrigins() != null && this.allowedOrigins().contains("*"))
            {
                result.addError("allowed_origins", "Cannot use wildcard '*' for origins when allow_credentials is true.");
            }
        }
    }

    private static final class GF implements ClientRequestGatewayFilter, ClientResponseGatewayFilter, ShortInfo
    {
        private static final ByteBuffer EMPTY_BODY = ByteBuffer.allocateDirect(0);

        private final String allowedMethodsString;
        private final String allowedHeadersString;
        private final String maxAge;
        private final boolean allowCredentials;
        private final boolean isAnyOrigin;
        private final Set<String> specificOrigins;

        public GF(final Config config)
        {
            this.allowedMethodsString = config.allowedMethods() != null ? String.join(",", config.allowedMethods()) : null;
            this.allowedHeadersString = config.allowedHeaders() != null ? String.join(",", config.allowedHeaders()) : null;
            this.maxAge = config.maxAge();
            this.allowCredentials = config.allowCredentials() != null && config.allowCredentials();

            final Set<String> originsConfig = config.allowedOrigins();

            if (originsConfig != null && originsConfig.size() == 1 && originsConfig.contains("*"))
            {
                this.isAnyOrigin = true;
                this.specificOrigins = Set.of();
            }
            else if (originsConfig != null)
            {
                this.isAnyOrigin = false;
                this.specificOrigins = Set.copyOf(originsConfig);
            }
            else
            {
                this.isAnyOrigin = false;
                this.specificOrigins = Set.of();
            }
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            final String method = exchange.clientRequest().method();

            if ("OPTIONS".equals(method))
            {
                final String origin = exchange.clientRequest().headers().getFirst(ORIGIN);
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

                    if (this.allowedMethodsString != null)
                    {
                        headers.set(ACCESS_CONTROL_ALLOW_METHODS, this.allowedMethodsString);
                    }
                    if (this.allowedHeadersString != null)
                    {
                        headers.set(ACCESS_CONTROL_ALLOW_HEADERS, this.allowedHeadersString);
                    }
                    if (this.maxAge != null)
                    {
                        headers.set(ACCESS_CONTROL_MAX_AGE, this.maxAge);
                    }
                    if (this.allowCredentials)
                    {
                        headers.set(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
                    }

                    exchange.shortCircuit(new ShortCircuitGatewayResponse(
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
            final String origin = exchange.clientRequest().headers().getFirst(ORIGIN);

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