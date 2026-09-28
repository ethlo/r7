package com.ethlo.r7.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;

/**
 * {@link CidrRange} is shared by the {@code RemoteAddr} predicate and the trusted-proxy check
 * in {@code UndertowGatewayRequest}; both need identical containment semantics for an operator's
 * mental model of a CIDR to hold in both places.
 */
class CidrRangeTest
{
    @Test
    void anExactAddressWithNoPrefixMatchesOnlyItself() throws UnknownHostException
    {
        final CidrRange range = CidrRange.parse("10.0.0.5");

        assertThat(range.contains(InetAddress.getByName("10.0.0.5"))).isTrue();
        assertThat(range.contains(InetAddress.getByName("10.0.0.6"))).isFalse();
    }

    @Test
    void aPrefixMatchesEveryAddressInTheSubnet() throws UnknownHostException
    {
        final CidrRange range = CidrRange.parse("10.0.0.0/8");

        assertThat(range.contains(InetAddress.getByName("10.0.0.1"))).isTrue();
        assertThat(range.contains(InetAddress.getByName("10.255.255.255"))).isTrue();
        assertThat(range.contains(InetAddress.getByName("11.0.0.1"))).isFalse();
    }

    @Test
    void ipv6IsSupported() throws UnknownHostException
    {
        final CidrRange range = CidrRange.parse("::1/128");

        assertThat(range.contains(InetAddress.getByName("::1"))).isTrue();
        assertThat(range.contains(InetAddress.getByName("::2"))).isFalse();
    }

    @Test
    void anAddressOfADifferentFamilyNeverMatches() throws UnknownHostException
    {
        final CidrRange range = CidrRange.parse("10.0.0.0/8");

        assertThat(range.contains(InetAddress.getByName("::1"))).isFalse();
    }

    @Test
    void nullNeverMatches()
    {
        assertThat(CidrRange.parse("0.0.0.0/0").contains(null)).isFalse();
    }

    @Test
    void unresolvableHostIsRejected()
    {
        assertThatIllegalArgumentException().isThrownBy(() -> CidrRange.parse("not-an-ip"));
    }

    @Test
    void aHostnameIsRejectedRatherThanResolvedViaDns()
    {
        // A literal-only parse: never a hostname, and therefore never a DNS lookup.
        assertThatIllegalArgumentException().isThrownBy(() -> CidrRange.parse("localhost"));
        assertThatIllegalArgumentException().isThrownBy(() -> CidrRange.parse("localhost/8"));
    }

    @Test
    void aPrefixLengthOutsideTheAddressFamilyRangeIsRejected()
    {
        assertThatIllegalArgumentException().isThrownBy(() -> CidrRange.parse("10.0.0.0/33"));
        assertThatIllegalArgumentException().isThrownBy(() -> CidrRange.parse("10.0.0.0/-1"));
    }

    @Test
    void aTrailingSlashWithNoPrefixIsRejectedRatherThanDefaultingToNoMask()
    {
        assertThatIllegalArgumentException().isThrownBy(() -> CidrRange.parse("10.0.0.1/"));
    }

    @Test
    void extraSegmentsAfterThePrefixAreRejectedRatherThanIgnored()
    {
        assertThatIllegalArgumentException().isThrownBy(() -> CidrRange.parse("10.0.0.1/24/extra"));
    }

    @Test
    void aLeadingSlashWithNoAddressIsRejected()
    {
        assertThatIllegalArgumentException().isThrownBy(() -> CidrRange.parse("/24"));
    }

    @Test
    void blankAndNullAreRejected()
    {
        assertThatIllegalArgumentException().isThrownBy(() -> CidrRange.parse(""));
        assertThatIllegalArgumentException().isThrownBy(() -> CidrRange.parse("   "));
        assertThatIllegalArgumentException().isThrownBy(() -> CidrRange.parse(null));
    }
}
