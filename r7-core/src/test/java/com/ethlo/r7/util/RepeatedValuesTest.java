package com.ethlo.r7.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.Cookie;
import com.ethlo.r7.api.Cookies;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.QueryParams;
import com.ethlo.r7.filters.RequireMatchQueryParameterFactory;
import com.ethlo.r7.predicates.MatchQueryParameterFactory;

/**
 * A value check passes only if every occurrence passes: {@code ?role=user&role=admin} must not
 * satisfy a check on {@code role}, whichever occurrence the upstream goes on to read.
 */
class RepeatedValuesTest
{
    private static final Pattern USER = Pattern.compile("user");

    private static Cookie cookie(final String name, final String value)
    {
        return new Cookie()
        {
            @Override
            public String name()
            {
                return name;
            }

            @Override
            public String value()
            {
                return value;
            }
        };
    }

    @Test
    void everyOccurrenceMustMatch()
    {
        assertThat(RepeatedValues.allMatch(List.of("user"), USER)).isTrue();
        assertThat(RepeatedValues.allMatch(List.of("user", "user"), USER)).isTrue();
        assertThat(RepeatedValues.allMatch(List.of("user", "admin"), USER)).isFalse();
        assertThat(RepeatedValues.allMatch(List.of("admin", "user"), USER)).isFalse();
    }

    @Test
    void anAbsentValueNeverMatches()
    {
        assertThat(RepeatedValues.allMatch(List.of(), USER)).isFalse();
        assertThat(RepeatedValues.allEqual(List.of(), "user")).isFalse();
    }

    @Test
    void everyOccurrenceMustEqual()
    {
        assertThat(RepeatedValues.allEqual(List.of("user", "user"), "user")).isTrue();
        assertThat(RepeatedValues.allEqual(List.of("user", "admin"), "user")).isFalse();
    }

    @Test
    void everyCookieWithTheNameMustMatch()
    {
        final Cookies cookies = mock(Cookies.class);
        when(cookies.all()).thenReturn(List.of(cookie("role", "user"), cookie("other", "x"), cookie("role", "admin")));

        assertThat(RepeatedValues.allCookiesMatch(cookies, "role", USER)).isFalse();
        assertThat(RepeatedValues.allCookiesEqual(cookies, "role", "user")).isFalse();
        assertThat(RepeatedValues.allCookiesMatch(cookies, "other", Pattern.compile("x"))).isTrue();
        assertThat(RepeatedValues.allCookiesMatch(cookies, "missing", USER)).isFalse();
    }

    @Test
    void theFilterAndThePredicateRefuseAPollutedParameter()
    {
        final QueryParams params = mock(QueryParams.class);
        when(params.getAll("role")).thenReturn(List.of("user", "admin"));
        final GatewayRequest request = mock(GatewayRequest.class);
        when(request.queryParams()).thenReturn(params);
        final ClientRequestGatewayExchange exchange = mock(ClientRequestGatewayExchange.class);
        when(exchange.clientRequest()).thenReturn(request);

        new RequireMatchQueryParameterFactory().create(new RequireMatchQueryParameterFactory.Config("role", "user", null), null)
                .onClientRequest(exchange);
        verify(exchange).shortCircuit(any());

        assertThat(new MatchQueryParameterFactory().create(new MatchQueryParameterFactory.Config("role", "user")).test(request)).isFalse();
    }

    @Test
    void theFilterAcceptsARepeatedParameterWhenEveryValueMatches()
    {
        final QueryParams params = mock(QueryParams.class);
        when(params.getAll("role")).thenReturn(List.of("user", "user"));
        final GatewayRequest request = mock(GatewayRequest.class);
        when(request.queryParams()).thenReturn(params);
        final ClientRequestGatewayExchange exchange = mock(ClientRequestGatewayExchange.class);
        when(exchange.clientRequest()).thenReturn(request);

        new RequireMatchQueryParameterFactory().create(new RequireMatchQueryParameterFactory.Config("role", "user", null), null)
                .onClientRequest(exchange);
        verify(exchange, never()).shortCircuit(any());
    }
}
