package com.ethlo.r7.filters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.MutableGatewayResponse;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.ShortCircuitGatewayResponse;
import com.ethlo.r7.util.constants.HttpStatuses;

class CorsFactoryTest
{
    private static final String ALLOWED = "https://app.example";

    private static Object filter(final Set<String> origins, final Boolean credentials)
    {
        return new CorsFactory().create(new CorsFactory.Config(origins, Set.of("GET", "POST"), Set.of("Content-Type"), "600", credentials), null);
    }

    private static GatewayRequest request(final String method, final String... headerPairs)
    {
        final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();
        for (int i = 0; i < headerPairs.length; i += 2)
        {
            headers.add(headerPairs[i], headerPairs[i + 1]);
        }
        final GatewayRequest request = mock(GatewayRequest.class);
        when(request.method()).thenReturn(method);
        when(request.headers()).thenReturn(headers);
        return request;
    }

    private static ShortCircuitGatewayResponse preflight(final Object filter, final GatewayRequest request)
    {
        final ClientRequestGatewayExchange exchange = mock(ClientRequestGatewayExchange.class);
        when(exchange.clientRequest()).thenReturn(request);
        ((ClientRequestGatewayFilter) filter).onClientRequest(exchange);
        final ArgumentCaptor<ShortCircuitGatewayResponse> response = ArgumentCaptor.forClass(ShortCircuitGatewayResponse.class);
        verify(exchange).shortCircuit(response.capture());
        return response.getValue();
    }

    private static MutableGatewayHeaders respond(final Object filter, final GatewayRequest request, final String... upstreamHeaderPairs)
    {
        final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();
        for (int i = 0; i < upstreamHeaderPairs.length; i += 2)
        {
            headers.add(upstreamHeaderPairs[i], upstreamHeaderPairs[i + 1]);
        }
        final MutableGatewayResponse response = mock(MutableGatewayResponse.class);
        when(response.headers()).thenReturn(headers);
        final ClientResponseGatewayExchange exchange = mock(ClientResponseGatewayExchange.class);
        when(exchange.clientRequest()).thenReturn(request);
        when(exchange.clientResponse()).thenReturn(response);
        ((ClientResponseGatewayFilter) filter).onClientResponse(exchange);
        return headers;
    }

    @Test
    void aPreflightFromAnAllowedOriginIsAnsweredWithTheFullPolicy()
    {
        final ShortCircuitGatewayResponse response = preflight(filter(Set.of(ALLOWED), true),
                request("OPTIONS", "Origin", ALLOWED, "Access-Control-Request-Method", "POST"));

        assertThat(response.status()).isEqualTo(HttpStatuses.NO_CONTENT);
        assertThat(response.headers().getFirst("Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
        assertThat(response.headers().getFirst("Access-Control-Allow-Credentials")).isEqualTo("true");
        assertThat(response.headers().getFirst("Vary")).isEqualTo("Origin");
    }

    @Test
    void aPreflightFromAnotherOriginLearnsNothing()
    {
        final ShortCircuitGatewayResponse response = preflight(filter(Set.of(ALLOWED), true),
                request("OPTIONS", "Origin", "https://evil.example", "Access-Control-Request-Method", "POST"));

        assertThat(response.headers().getFirst("Access-Control-Allow-Origin")).isNull();
        assertThat(response.headers().getFirst("Access-Control-Allow-Methods")).isNull();
        assertThat(response.headers().getFirst("Access-Control-Allow-Credentials")).isNull();
    }

    /**
     * An OPTIONS request that is not a preflight is the upstream's to answer.
     */
    @Test
    void anOptionsRequestThatIsNotAPreflightGoesUpstream()
    {
        final GatewayRequest request = request("OPTIONS", "Origin", ALLOWED);
        final ClientRequestGatewayExchange exchange = mock(ClientRequestGatewayExchange.class);
        when(exchange.clientRequest()).thenReturn(request);

        ((ClientRequestGatewayFilter) filter(Set.of(ALLOWED), false)).onClientRequest(exchange);

        verify(exchange, never()).shortCircuit(any());
    }

    /**
     * With a list of origins every response carries Vary: Origin, also one to a request without
     * Origin, so a shared cache never hands one origin's answer to another.
     */
    @Test
    void responsesVaryByOriginWhenTheAnswerDependsOnIt()
    {
        assertThat(respond(filter(Set.of(ALLOWED), false), request("GET")).getAll("Vary")).containsExactly("Origin");
        assertThat(respond(filter(Set.of(ALLOWED), false), request("GET", "Origin", ALLOWED), "Vary", "Accept-Encoding").getAll("Vary"))
                .containsExactly("Accept-Encoding", "Origin");
        assertThat(respond(filter(Set.of(ALLOWED), false), request("GET"), "Vary", "accept, origin").getAll("Vary"))
                .containsExactly("accept, origin");
        assertThat(respond(filter(Set.of("*"), false), request("GET", "Origin", ALLOWED)).getAll("Vary")).isEmpty();
    }

    /**
     * The gateway's CORS policy is the one in force: an upstream that grants a disallowed origin
     * anyway is overruled.
     */
    @Test
    void anUpstreamGrantToADisallowedOriginIsRemoved()
    {
        final MutableGatewayHeaders headers = respond(filter(Set.of(ALLOWED), false),
                request("GET", "Origin", "https://evil.example"),
                "Access-Control-Allow-Origin", "https://evil.example",
                "Access-Control-Allow-Credentials", "true");

        assertThat(headers.getFirst("Access-Control-Allow-Origin")).isNull();
        assertThat(headers.getFirst("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    void anAllowedOriginIsGrantedOnTheResponse()
    {
        final MutableGatewayHeaders headers = respond(filter(Set.of(ALLOWED), true), request("GET", "Origin", ALLOWED));

        assertThat(headers.getFirst("Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
        assertThat(headers.getFirst("Access-Control-Allow-Credentials")).isEqualTo("true");
    }
}
