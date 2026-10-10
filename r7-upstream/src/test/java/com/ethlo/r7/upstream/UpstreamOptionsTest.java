package com.ethlo.r7.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.config.TargetConfig;
import com.ethlo.r7.config.TimeoutConfig;
import com.ethlo.r7.config.UpstreamConfig;
import com.ethlo.r7.server.config.ServerConfig;

/**
 * {@link UpstreamOptions#of} is the one place a route's timeouts reach the upstream client.
 */
class UpstreamOptionsTest
{
    @Test
    void aRoutesTimeoutsReachTheClient()
    {
        final UpstreamOptions options = UpstreamOptions.of(ServerConfig.standard(),
                upstream(new TimeoutConfig(Duration.ofSeconds(7), Duration.ofMillis(250))));

        assertThat(options.readTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(options.connectTimeout()).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void anUpstreamWithoutTimeoutsGetsTheDefaults()
    {
        final UpstreamOptions options = UpstreamOptions.of(ServerConfig.standard(), upstream(null));

        assertThat(options.readTimeout()).isEqualTo(TimeoutConfig.DEFAULT_READ);
        assertThat(options.connectTimeout()).isEqualTo(TimeoutConfig.DEFAULT_CONNECT);
    }

    private static UpstreamConfig upstream(final TimeoutConfig timeouts)
    {
        return new UpstreamConfig(null, null, timeouts, List.of(new TargetConfig("http://localhost:1")), null);
    }
}
