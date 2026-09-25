package com.ethlo.r7.filters;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.TextValues;
import com.ethlo.r7.config.model.HttpStatus;
import com.ethlo.r7.doc.DefaultValue;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ShortCircuitGatewayResponse;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.util.constants.MediaTypes;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
@Description("Redirects requests based on a path pattern match using a template for the target URL.")
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
    public ClientRequestGatewayFilter create(final Config config, final FilterCreationContext filterCreationContext)
    {
        return new GF(config);
    }

    public record Config(
            @Description("The regular expression pattern to match against the request path.")
            String source,

            @Description("The replacement template for the target URL (e.g., /new/$1).")
            String target,

            @Description("The HTTP status code to return for the redirect.")
            @DefaultValue("302")
            HttpStatus status) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result)
                    .requiredRegexp("source", this.source())
                    .notBlank("target", this.target())
                    .safeHeaderText("target", this.target())
                    .validRegexReplacement("target", this.source(), this.target());
        }
    }

    private static final class GF implements ClientRequestGatewayFilter, ShortInfo
    {
        private final Pattern sourcePattern;
        private final String targetTemplate;
        private final int responseStatus;
        private final ByteBuffer invalidLocationBody;

        public GF(final Config config)
        {
            this.sourcePattern = Pattern.compile(config.source());

            // Consume the standard regex string exactly as provided
            this.targetTemplate = config.target();

            if (config.status() != null)
            {
                this.responseStatus = config.status().code();
            }
            else
            {
                this.responseStatus = HttpStatuses.FOUND;
            }

            this.invalidLocationBody = ByteBuffer.wrap(
                    "Redirect target could not be computed for this request".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            final String currentPath = exchange.clientRequest().path();
            final Matcher matcher = this.sourcePattern.matcher(currentPath);

            if (matcher.find())
            {
                final String location = matcher.replaceFirst(this.targetTemplate);

                // The template itself is validated at startup, but a capture group (e.g. $1) is
                // filled in from the request path, which Undertow hands us already URL-decoded.
                // A crafted path can therefore inject a control character into the computed
                // location; MutableFastGatewayHeaders.set only rejects code points above
                // ISO-8859-1, not CR/LF, so that would otherwise reach the wire as response
                // splitting. Reject the request instead of emitting a malformed Location.
                if (!isSafeLocation(location))
                {
                    exchange.shortCircuit(new ShortCircuitGatewayResponse(
                            HttpStatuses.BAD_REQUEST,
                            MediaTypes.TEXT_PLAIN,
                            this.invalidLocationBody.asReadOnlyBuffer()
                    ));
                    return;
                }

                final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders(1);
                headers.set(HttpHeaders.LOCATION, location);

                exchange.shortCircuit(new ShortCircuitGatewayResponse(
                        headers,
                        this.responseStatus,
                        EMPTY_BODY.slice()
                ));
            }
        }

        private static boolean isSafeLocation(final String location)
        {
            // Unlike a header value, a Location carries a URI-reference; RFC 3986 has no
            // "obs-fold" concept, so unlike safeHeaderText, HTAB is not treated as safe here.
            for (int i = 0, len = location.length(); i < len; i++)
            {
                final char character = location.charAt(i);
                if (character > TextValues.MAX_STORABLE || character == 0x7F || character < 0x20)
                {
                    return false;
                }
            }
            return true;
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