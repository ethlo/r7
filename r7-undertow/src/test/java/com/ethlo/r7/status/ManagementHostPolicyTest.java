package com.ethlo.r7.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ManagementHostPolicyTest
{
    private final ManagementHostPolicy policy = new ManagementHostPolicy("127.0.0.1", List.of("status.internal.example"));

    @Test
    void loopbackNamesAndAddressesAreAccepted()
    {
        assertThat(policy.allows("localhost")).isTrue();
        assertThat(policy.allows("localhost:18888")).isTrue();
        assertThat(policy.allows("LOCALHOST.:18888")).isTrue();
        assertThat(policy.allows("127.0.0.1:18888")).isTrue();
        assertThat(policy.allows("[::1]:18888")).isTrue();
        assertThat(policy.allows("[::1]")).isTrue();
    }

    @Test
    void anyIpLiteralIsAcceptedSinceRebindingNeedsAName()
    {
        assertThat(policy.allows("10.1.2.3:18888")).isTrue();
        assertThat(policy.allows("[fe80::1]:18888")).isTrue();
    }

    @Test
    void configuredNamesAreAccepted()
    {
        assertThat(policy.allows("status.internal.example:19999")).isTrue();
        assertThat(policy.allows("Status.Internal.Example")).isTrue();
        assertThat(new ManagementHostPolicy("gateway-mgmt.local", List.of()).allows("gateway-mgmt.local:18888")).isTrue();
    }

    /**
     * What a DNS-rebinding page sends: its own name, now resolving to this host.
     */
    @Test
    void otherNamesAreRefused()
    {
        assertThat(policy.allows("attacker.example")).isFalse();
        assertThat(policy.allows("attacker.example:18888")).isFalse();
        assertThat(policy.allows("127.0.0.1.attacker.example")).isFalse();
        assertThat(policy.allows("localhost.attacker.example")).isFalse();
        assertThat(policy.allows("[attacker.example]")).isFalse();
        assertThat(policy.allows("")).isFalse();
    }

    @Test
    void aRequestWithoutHostIsNotFromABrowser()
    {
        assertThat(policy.allows(null)).isTrue();
    }
}
