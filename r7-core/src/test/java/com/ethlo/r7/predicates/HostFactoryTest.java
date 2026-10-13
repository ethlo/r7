package com.ethlo.r7.predicates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.validation.ValidationResult;

class HostFactoryTest
{
    private static GatewayPredicate predicate(final String... hosts)
    {
        return new HostFactory().create(new HostFactory.Config(List.of(hosts)));
    }

    private static GatewayRequest requestWithHost(final String host)
    {
        final GatewayHeaders headers = mock(GatewayHeaders.class);
        when(headers.getFirst("host")).thenReturn(host);
        final GatewayRequest request = mock(GatewayRequest.class);
        when(request.headers()).thenReturn(headers);
        return request;
    }

    @Test
    void anEntryHoldingACommaSeparatedListMatchesEachHost()
    {
        final GatewayPredicate predicate = predicate("a.example.com, b.example.com,c.example.com");

        assertThat(predicate.test(requestWithHost("a.example.com"))).isTrue();
        assertThat(predicate.test(requestWithHost("B.example.com:9000"))).isTrue();
        assertThat(predicate.test(requestWithHost("c.example.com"))).isTrue();
        assertThat(predicate.test(requestWithHost("d.example.com"))).isFalse();
    }

    @Test
    void separateEntriesStillWork()
    {
        final GatewayPredicate predicate = predicate("a.example.com", "b.example.com");

        assertThat(predicate.test(requestWithHost("b.example.com"))).isTrue();
        assertThat(predicate.test(requestWithHost("c.example.com"))).isFalse();
    }

    @Test
    void anEntryThatHoldsNoHostIsRefused()
    {
        final ValidationResult result = new ValidationResult();
        new HostFactory.Config(List.of(" , ")).validate(result);

        assertThat(result.getErrors()).singleElement().asString().contains("hosts", "no host name");
    }
}
