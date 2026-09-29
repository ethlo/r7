package com.ethlo.r7.undertow;

import java.util.concurrent.atomic.AtomicInteger;

import com.ethlo.r7.config.Strategy;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;

/**
 * Maps an upstream's configured {@link Strategy} to the host selector its proxy client uses.
 * <p>
 * The switch is exhaustive on purpose: a strategy added to the enum does not compile until it
 * selects hosts, so the configuration cannot offer a value that is silently ignored.
 */
public final class UpstreamHostSelectors
{
    private UpstreamHostSelectors()
    {
    }

    public static LoadBalancingProxyClient.HostSelector forStrategy(final Strategy strategy)
    {
        return switch (strategy)
        {
            case ROUND_ROBIN -> new RoundRobin();
        };
    }

    /**
     * The index is where the proxy client starts looking; it walks on from there past hosts that
     * are full or failing, so this only has to spread the starting points evenly.
     * <p>
     * Not Undertow's own round robin: that one takes {@code counter % hosts}, which goes negative
     * once the counter wraps after 2^31 requests and indexes out of the host array for any route
     * with more than one target.
     */
    static final class RoundRobin implements LoadBalancingProxyClient.HostSelector
    {
        private final AtomicInteger counter;

        RoundRobin()
        {
            this(0);
        }

        RoundRobin(final int start)
        {
            this.counter = new AtomicInteger(start);
        }

        @Override
        public int selectHost(final LoadBalancingProxyClient.Host[] availableHosts)
        {
            return Math.floorMod(this.counter.getAndIncrement(), availableHosts.length);
        }

        @Override
        public int selectHost(final LoadBalancingProxyClient.Host[] availableHosts, final HttpServerExchange exchange)
        {
            return this.selectHost(availableHosts);
        }
    }
}
