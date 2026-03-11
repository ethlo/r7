package com.ethlo.r7.filters;

import java.nio.ByteBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.core.ShortInfo;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.FastTerminationGatewayResponse;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public final class TemplateRedirectFactory implements GatewayFilterFactory<TemplateRedirectFactory.Config>
{
    private static final String FILTER_NAME = "TemplateRedirect";
    private static final ByteBuffer EMPTY_BODY = ByteBuffer.allocateDirect(0);

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

    public record Config(String source, String target, Integer status) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validatorUtils = new ValidatorUtils(result)
                    .required(FILTER_NAME, "source", this.source())
                    .required(FILTER_NAME, "target", this.target());

            if (this.source() != null)
            {
                try
                {
                    Pattern.compile(this.source());
                }
                catch (final PatternSyntaxException e)
                {
                    validatorUtils.invalid(FILTER_NAME, "source", this.source(), "Invalid regex pattern: " + e.getPattern());
                }
            }
        }
    }

    private static final class GF implements ClientRequestGatewayFilter, ShortInfo
    {
        private final Pattern sourcePattern;
        private final String targetTemplate;
        private final int responseStatus;

        public GF(final Config config)
        {
            this.sourcePattern = Pattern.compile(config.source());

            // Convert the user-friendly {{1}}, {{var}} syntax to Java Matcher $1, ${var} syntax
            this.targetTemplate = config.target().replaceAll("\\{\\{(\\w+)\\}\\}", "\\$$1");

            if (config.status() != null)
            {
                this.responseStatus = config.status();
            }
            else
            {
                this.responseStatus = HttpStatuses.FOUND; // 302 default
            }
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            final String currentPath = exchange.clientRequest().path().toString();
            final Matcher matcher = this.sourcePattern.matcher(currentPath);

            if (matcher.find())
            {
                final String location = matcher.replaceFirst(this.targetTemplate);

                final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders(1);
                headers.set(HttpHeaders.LOCATION, location);

                exchange.shortCircuit(new FastTerminationGatewayResponse(
                        this.responseStatus,
                        null,
                        EMPTY_BODY.slice()
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
            return FILTER_NAME + ": " + this.responseStatus + " -> " + this.targetTemplate;
        }
    }
}