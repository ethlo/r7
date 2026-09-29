package com.ethlo.r7.filters;

import static com.ethlo.r7.util.constants.HttpHeaders.X_CORRELATION_ID;

import java.util.Optional;

import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.StateKey;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.doc.DefaultValue;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.doc.Nullable;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
@Description("Adds a unique correlation ID header to upstream requests and client responses for request tracing.")
public final class AddCorrelationIdFactory implements GatewayFilterFactory<AddCorrelationIdFactory.Config>
{
    private static final String FILTER_NAME = "AddCorrelationId";

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
    public GatewayFilter create(final Config config, final FilterCreationContext filterCreationContext)
    {
        return new GF(config);
    }

    public record Config(
            @Nullable
            @DefaultValue("false")
            @Description("Whether an incoming X-Correlation-Id from the client is kept instead of being "
                    + "replaced. Off by default: the header is client-controlled, so trusting it lets a "
                    + "client plant an arbitrary value in upstream logs and tracing under this gateway's name.")
            Boolean trustIncoming) implements ValidatableConfig
    {
        @Override
        public Boolean trustIncoming()
        {
            return Optional.ofNullable(this.trustIncoming).orElse(false);
        }

        @Override
        public void validate(final ValidationResult result)
        {
            // No required fields: an all-defaults filter is a valid, and the common, configuration.
        }
    }

    private static final class GF implements UpstreamRequestGatewayFilter, ClientResponseGatewayFilter, ShortInfo
    {
        private static final StateKey<String> RESOLVED_CORRELATION_ID = new StateKey<>("add_correlation_id.resolved");

        private final boolean trustIncoming;

        public GF(final Config config)
        {
            this.trustIncoming = config.trustIncoming();
        }

        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            // trustIncoming=false (the default): a client-supplied value must not reach the
            // upstream at all, so it is replaced outright rather than appended alongside it -
            // .add() would leave both values on the wire for a downstream reader to pick either.
            final String incoming = exchange.upstreamRequest().headers().getFirst(X_CORRELATION_ID);
            final String resolved = (this.trustIncoming && incoming != null) ? incoming : exchange.requestId();
            exchange.upstreamRequest().headers().set(X_CORRELATION_ID, resolved);
            exchange.setAttachment(RESOLVED_CORRELATION_ID, resolved);
        }

        @Override
        public void onClientResponse(final ClientResponseGatewayExchange exchange)
        {
            // Echoes whatever was actually sent upstream (the client's own ID when trusted and
            // present, r7's own otherwise) rather than always r7's ID, so the client sees the
            // correlation ID its own logs and the upstream's logs were tied together under.
            final String resolved = exchange.getAttachment(RESOLVED_CORRELATION_ID);
            exchange.clientResponse().headers().set(X_CORRELATION_ID, resolved != null ? resolved : exchange.requestId());
        }

        @Override
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + X_CORRELATION_ID + (this.trustIncoming ? " (trusts incoming)" : "");
        }
    }
}