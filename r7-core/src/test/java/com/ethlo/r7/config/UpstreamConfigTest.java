package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class UpstreamConfigTest
{
    @Test
    void strategyDefaultsToRoundRobin()
    {
        final UpstreamConfig config = new UpstreamConfig(null, null, null, List.of(), null);
        assertThat(config.strategy()).isEqualTo(Strategy.ROUND_ROBIN);
    }
}
