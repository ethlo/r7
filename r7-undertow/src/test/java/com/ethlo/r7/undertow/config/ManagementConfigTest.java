package com.ethlo.r7.undertow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

class ManagementConfigTest
{
    /**
     * The endpoint is unauthenticated, so without configuration it must not be reachable from
     * other hosts.
     */
    @Test
    void bindsToLoopbackByDefault()
    {
        assumeTrue(System.getenv(ServerConfig.ManagementConfig.HOST_ENVIRONMENT_VARIABLE) == null,
                "R7_MANAGEMENT_HOST is set in this environment");

        assertThat(new ServerConfig.ManagementConfig(null, null).host()).isEqualTo("127.0.0.1");
        assertThat(ServerConfig.standard().management().host()).isEqualTo("127.0.0.1");
    }

    @Test
    void anExplicitHostWins()
    {
        assertThat(new ServerConfig.ManagementConfig("10.0.0.5", null).host()).isEqualTo("10.0.0.5");
    }
}
