package com.ethlo.r7.filters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.validation.ValidationResult;

class RateLimiterFactoryTest
{
    private static ClientRequestGatewayFilter limiter(final Integer ipv6PrefixLength)
    {
        return new RateLimiterFactory().create(new RateLimiterFactory.Config(1L, 1L, Duration.ofHours(1), null, null, ipv6PrefixLength), null);
    }

    private static String key(final String address, final int prefixLength)
    {
        return RateLimiterFactory.clientKey(address != null ? InetAddress.ofLiteral(address) : null, prefixLength);
    }

    private static ClientRequestGatewayExchange from(final String address) throws Exception
    {
        final GatewayRequest request = mock(GatewayRequest.class);
        when(request.remoteAddress()).thenReturn(address != null ? InetAddress.ofLiteral(address) : null);
        final ClientRequestGatewayExchange exchange = mock(ClientRequestGatewayExchange.class);
        when(exchange.clientRequest()).thenReturn(request);
        return exchange;
    }

    /**
     * One subscriber typically holds a whole /64: rotating the interface identifier must not buy
     * a fresh bucket.
     */
    @Test
    void addressesInOneIpv6Slash64ShareABucket() throws Exception
    {
        final ClientRequestGatewayFilter filter = limiter(null);
        final ClientRequestGatewayExchange first = from("2001:db8:1:2::1");
        final ClientRequestGatewayExchange rotated = from("2001:db8:1:2:ffff:ffff:ffff:ffff");

        filter.onClientRequest(first);
        filter.onClientRequest(rotated);

        verify(first, never()).shortCircuit(any());
        verify(rotated).shortCircuit(any());
    }

    @Test
    void differentIpv6Slash64sAndIpv4AddressesDoNot() throws Exception
    {
        final ClientRequestGatewayFilter filter = limiter(null);
        final ClientRequestGatewayExchange a = from("2001:db8:1:2::1");
        final ClientRequestGatewayExchange b = from("2001:db8:1:3::1");
        final ClientRequestGatewayExchange c = from("192.0.2.1");
        final ClientRequestGatewayExchange d = from("192.0.2.2");

        filter.onClientRequest(a);
        filter.onClientRequest(b);
        filter.onClientRequest(c);
        filter.onClientRequest(d);

        verify(a, never()).shortCircuit(any());
        verify(b, never()).shortCircuit(any());
        verify(c, never()).shortCircuit(any());
        verify(d, never()).shortCircuit(any());
    }

    @Test
    void theKeyKeepsExactlyThePrefix() throws Exception
    {
        assertThat(key("2001:db8:1:2:3:4:5:6", 64)).isEqualTo("20010db8000100020000000000000000/64");
        assertThat(key("2001:db8:1:2:3:4:5:6", 56)).isEqualTo("20010db8000100000000000000000000/56");
        assertThat(key("2001:db8:1:2:3:4:5:6", 60)).isEqualTo("20010db8000100000000000000000000/60");
        assertThat(key("2001:db8:1:2:3:4:5:6", 128)).isEqualTo("20010db8000100020003000400050006/128");
        assertThat(key("192.0.2.7", 64)).isEqualTo("192.0.2.7");
        assertThat(key(null, 64)).isEqualTo("unknown");
    }

    @Test
    void aPrefixLengthOutsideIpv6IsRejected()
    {
        final ValidationResult tooLong = new ValidationResult();
        new RateLimiterFactory.Config(1L, 1L, Duration.ofSeconds(1), null, null, 129).validate(tooLong);
        assertThat(tooLong.hasErrors()).isTrue();

        final ValidationResult zero = new ValidationResult();
        new RateLimiterFactory.Config(1L, 1L, Duration.ofSeconds(1), null, null, 0).validate(zero);
        assertThat(zero.hasErrors()).isTrue();
    }
}
