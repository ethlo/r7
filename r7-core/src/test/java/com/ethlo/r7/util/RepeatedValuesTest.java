package com.ethlo.r7.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
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

    private static QueryParams params(final String name, final List<String> values)
    {
        final QueryParams params = mock(QueryParams.class);
        when(params.count(name)).thenReturn(values.size());
        when(params.getFirst(name)).thenReturn(values.isEmpty() ? null : values.getFirst());
        when(params.getAll(name)).thenReturn(values);
        return params;
    }

    private static MutableFastGatewayHeaders cookies(final String... lines)
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        for (final String line : lines)
        {
            headers.add("Cookie", line);
        }
        return headers;
    }

    @Test
    void everyOccurrenceMustMatch()
    {
        assertThat(RepeatedValues.allMatch(params("role", List.of("user")), "role", USER)).isTrue();
        assertThat(RepeatedValues.allMatch(params("role", List.of("user", "user")), "role", USER)).isTrue();
        assertThat(RepeatedValues.allMatch(params("role", List.of("user", "admin")), "role", USER)).isFalse();
        assertThat(RepeatedValues.allMatch(params("role", List.of("admin", "user")), "role", USER)).isFalse();
    }

    @Test
    void anAbsentValueNeverMatches()
    {
        assertThat(RepeatedValues.allMatch(params("role", List.of()), "role", USER)).isFalse();
        assertThat(RepeatedValues.allEqual(params("role", List.of()), "role", "user")).isFalse();
        assertThat(RepeatedValues.allMatch(Collections.emptyList(), USER)).isFalse();
    }

    @Test
    void everyOccurrenceMustEqual()
    {
        assertThat(RepeatedValues.allEqual(params("role", List.of("user", "user")), "role", "user")).isTrue();
        assertThat(RepeatedValues.allEqual(params("role", List.of("user", "admin")), "role", "user")).isFalse();
    }

    /**
     * Undertow keeps only the last cookie of a name and many upstream parsers the first, so the
     * check reads the raw header: both orders, and both one line and two, are refused.
     */
    @Test
    void everyCookieWithTheNameMustMatch()
    {
        assertThat(RepeatedValues.allCookiesMatch(cookies("role=user; role=admin"), "role", USER)).isFalse();
        assertThat(RepeatedValues.allCookiesMatch(cookies("role=admin; role=user"), "role", USER)).isFalse();
        assertThat(RepeatedValues.allCookiesMatch(cookies("role=user", "role=admin"), "role", USER)).isFalse();
        assertThat(RepeatedValues.allCookiesEqual(cookies("role=admin;role=user"), "role", "user")).isFalse();

        assertThat(RepeatedValues.allCookiesMatch(cookies("a=1; role=user; xrole=admin; role=\"user\""), "role", USER)).isTrue();
        assertThat(RepeatedValues.allCookiesEqual(cookies("session=x; role = user "), "role", "user")).isTrue();
        // Not valid RFC 6265, but a lenient upstream trims the name and sees a second role.
        assertThat(RepeatedValues.allCookiesMatch(cookies("role=user; role =admin"), "role", USER)).isFalse();
        assertThat(RepeatedValues.allCookiesMatch(cookies("role=user;\trole=admin"), "role", USER)).isFalse();
        assertThat(RepeatedValues.allCookiesMatch(cookies("other=user"), "role", USER)).isFalse();
        assertThat(RepeatedValues.allCookiesMatch(new MutableFastGatewayHeaders(), "role", USER)).isFalse();
    }

    /**
     * One budget for the whole check: a value that runs just under the limit cannot be repeated
     * to multiply the work.
     */
    @Test
    void repeatedValuesShareOneRegexBudget()
    {
        // The first alternative backtracks through roughly 356,000 character reads before failing,
        // then ".*" matches: an expensive value that still passes. Within the budget once, not
        // five times.
        final Pattern slow = Pattern.compile("(.*a){4}b|.*");
        final String value = "a".repeat(40) + "!";
        final List<String> many = Collections.nCopies(5, value);

        assertThat(RepeatedValues.allMatch(List.of(value), slow)).as("one occurrence matches within the budget").isTrue();
        assertThatThrownBy(() -> RepeatedValues.allMatch(many, slow))
                .isInstanceOf(RegexBudget.RegexBudgetExceededException.class);
    }

    @Test
    void theFilterAndThePredicateRefuseAPollutedParameter()
    {
        final QueryParams params = params("role", List.of("user", "admin"));
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
        final QueryParams params = params("role", List.of("user", "user"));
        final GatewayRequest request = mock(GatewayRequest.class);
        when(request.queryParams()).thenReturn(params);
        final ClientRequestGatewayExchange exchange = mock(ClientRequestGatewayExchange.class);
        when(exchange.clientRequest()).thenReturn(request);

        new RequireMatchQueryParameterFactory().create(new RequireMatchQueryParameterFactory.Config("role", "user", null), null)
                .onClientRequest(exchange);
        verify(exchange, never()).shortCircuit(any());
    }

    /**
     * Segments without '=' are skipped without rescanning the rest of the line; a quadratic scan
     * over a large admitted header would take seconds on the I/O thread.
     */
    @Test
    void manySegmentsWithoutEqualsAreScannedInLinearTime()
    {
        final String line = "x;".repeat(30_000) + "role=user";
        final long start = System.nanoTime();
        assertThat(RepeatedValues.allCookiesMatch(cookies(line), "role", USER)).isTrue();
        assertThat(System.nanoTime() - start).isLessThan(100_000_000L);
    }
}
