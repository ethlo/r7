package com.ethlo.r7.filters;

import static com.ethlo.r7.util.constants.HttpHeaders.X_CORRELATION_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.MutableGatewayRequest;
import com.ethlo.r7.api.MutableGatewayResponse;
import com.ethlo.r7.api.StateKey;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * L3: a client-supplied X-Correlation-Id must not reach the upstream (or come back to the
 * client) unless the operator explicitly opts into trusting it - otherwise a client can plant
 * an arbitrary value in upstream logs and tracing under this gateway's name.
 */
class AddCorrelationIdFactoryTest
{
    /**
     * Backs setAttachment()/getAttachment() with a shared map so the value the upstream-request
     * phase resolves is visible to the client-response phase, exactly as the real exchange
     * carries a StateKey attachment across phases of the same request.
     */
    private static final class SharedState
    {
        private final Map<StateKey<?>, Object> values = new HashMap<>();

        @SuppressWarnings("unchecked")
        <T> T get(final StateKey<T> key)
        {
            return (T) values.get(key);
        }

        <T> void set(final StateKey<T> key, final T value)
        {
            values.put(key, value);
        }
    }

    private static UpstreamRequestGatewayExchange upstreamExchange(final SharedState state, final String requestId, final MutableFastGatewayHeaders upstreamHeaders)
    {
        final MutableGatewayRequest upstreamRequest = mock(MutableGatewayRequest.class);
        when(upstreamRequest.headers()).thenReturn(upstreamHeaders);

        final UpstreamRequestGatewayExchange exchange = mock(UpstreamRequestGatewayExchange.class);
        when(exchange.requestId()).thenReturn(requestId);
        when(exchange.upstreamRequest()).thenReturn(upstreamRequest);
        doAnswerAttachments(exchange, state);
        return exchange;
    }

    private static ClientResponseGatewayExchange responseExchange(final SharedState state, final String requestId, final MutableFastGatewayHeaders responseHeaders)
    {
        final MutableGatewayResponse response = mock(MutableGatewayResponse.class);
        when(response.headers()).thenReturn(responseHeaders);

        final ClientResponseGatewayExchange exchange = mock(ClientResponseGatewayExchange.class);
        when(exchange.requestId()).thenReturn(requestId);
        when(exchange.clientResponse()).thenReturn(response);
        doAnswerAttachments(exchange, state);
        return exchange;
    }

    @SuppressWarnings("unchecked")
    private static void doAnswerAttachments(final com.ethlo.r7.api.GatewayExchange exchange, final SharedState state)
    {
        when(exchange.getAttachment(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> state.get(inv.getArgument(0)));
        org.mockito.Mockito.doAnswer(inv ->
        {
            state.set(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(exchange).setAttachment(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void byDefaultAClientSuppliedIdIsReplacedNotAppended()
    {
        final AddCorrelationIdFactory.Config config = new AddCorrelationIdFactory.Config(null);
        final UpstreamRequestGatewayFilter filter = (UpstreamRequestGatewayFilter) new AddCorrelationIdFactory().create(config, null);

        final SharedState state = new SharedState();
        final MutableFastGatewayHeaders upstreamHeaders = new MutableFastGatewayHeaders();
        upstreamHeaders.set(X_CORRELATION_ID, "attacker-supplied");

        filter.onUpstreamRequest(upstreamExchange(state, "r7-generated-id", upstreamHeaders));

        // Exactly one value reaches the upstream, and it is r7's own - never the client's.
        assertThat(upstreamHeaders.getFirst(X_CORRELATION_ID)).isEqualTo("r7-generated-id");
        int count = upstreamHeaders.forEach((name, value) -> {});
        assertThat(count).isEqualTo(1);
    }

    @Test
    void byDefaultTheClientResponseEchoesR7sOwnId()
    {
        final AddCorrelationIdFactory.Config config = new AddCorrelationIdFactory.Config(null);
        final Object factoryFilter = new AddCorrelationIdFactory().create(config, null);
        final UpstreamRequestGatewayFilter upstreamFilter = (UpstreamRequestGatewayFilter) factoryFilter;
        final ClientResponseGatewayFilter responseFilter = (ClientResponseGatewayFilter) factoryFilter;

        final SharedState state = new SharedState();
        final MutableFastGatewayHeaders upstreamHeaders = new MutableFastGatewayHeaders();
        upstreamHeaders.set(X_CORRELATION_ID, "attacker-supplied");
        upstreamFilter.onUpstreamRequest(upstreamExchange(state, "r7-generated-id", upstreamHeaders));

        final MutableFastGatewayHeaders responseHeaders = new MutableFastGatewayHeaders();
        responseFilter.onClientResponse(responseExchange(state, "r7-generated-id", responseHeaders));

        assertThat(responseHeaders.getFirst(X_CORRELATION_ID)).isEqualTo("r7-generated-id");
    }

    @Test
    void whenTrustedAnIncomingIdSurvivesToTheUpstreamAndTheResponse()
    {
        final AddCorrelationIdFactory.Config config = new AddCorrelationIdFactory.Config(true);
        final Object factoryFilter = new AddCorrelationIdFactory().create(config, null);
        final UpstreamRequestGatewayFilter upstreamFilter = (UpstreamRequestGatewayFilter) factoryFilter;
        final ClientResponseGatewayFilter responseFilter = (ClientResponseGatewayFilter) factoryFilter;

        final SharedState state = new SharedState();
        final MutableFastGatewayHeaders upstreamHeaders = new MutableFastGatewayHeaders();
        upstreamHeaders.set(X_CORRELATION_ID, "client-trusted-id");
        upstreamFilter.onUpstreamRequest(upstreamExchange(state, "r7-generated-id", upstreamHeaders));

        assertThat(upstreamHeaders.getFirst(X_CORRELATION_ID)).isEqualTo("client-trusted-id");

        final MutableFastGatewayHeaders responseHeaders = new MutableFastGatewayHeaders();
        responseFilter.onClientResponse(responseExchange(state, "r7-generated-id", responseHeaders));

        assertThat(responseHeaders.getFirst(X_CORRELATION_ID)).isEqualTo("client-trusted-id");
    }

    @Test
    void whenTrustedButAbsentR7StillGeneratesOne()
    {
        final AddCorrelationIdFactory.Config config = new AddCorrelationIdFactory.Config(true);
        final UpstreamRequestGatewayFilter filter = (UpstreamRequestGatewayFilter) new AddCorrelationIdFactory().create(config, null);

        final SharedState state = new SharedState();
        final MutableFastGatewayHeaders upstreamHeaders = new MutableFastGatewayHeaders();

        filter.onUpstreamRequest(upstreamExchange(state, "r7-generated-id", upstreamHeaders));

        assertThat(upstreamHeaders.getFirst(X_CORRELATION_ID)).isEqualTo("r7-generated-id");
    }
}
