package com.ethlo.r7.undertow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.config.Strategy;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;

class UpstreamHostSelectorsTest
{
    private static final LoadBalancingProxyClient.Host[] THREE_HOSTS = new LoadBalancingProxyClient.Host[3];

    @Test
    void everyStrategyHasASelector()
    {
        for (final Strategy strategy : Strategy.values())
        {
            assertThat(UpstreamHostSelectors.forStrategy(strategy)).as(strategy.name()).isNotNull();
        }
    }

    @Test
    void roundRobinVisitsEveryHostInTurn()
    {
        final LoadBalancingProxyClient.HostSelector selector = UpstreamHostSelectors.forStrategy(Strategy.ROUND_ROBIN);
        final int[] picks = new int[6];
        for (int i = 0; i < picks.length; i++)
        {
            picks[i] = selector.selectHost(THREE_HOSTS);
        }
        assertThat(picks).containsExactly(0, 1, 2, 0, 1, 2);
    }

    @Test
    void roundRobinStaysInsideTheHostArrayWhenTheCounterWraps()
    {
        // Undertow's own selector returns counter % hosts, which is negative past the wrap and
        // indexes out of the host array after 2^31 requests.
        final UpstreamHostSelectors.RoundRobin selector = new UpstreamHostSelectors.RoundRobin(Integer.MAX_VALUE - 2);
        for (int i = 0; i < 10; i++)
        {
            assertThat(selector.selectHost(THREE_HOSTS)).isBetween(0, THREE_HOSTS.length - 1);
        }
    }
}
