package com.ethlo.r7.server;

import com.ethlo.r7.config.DefaultGatewayRoute;

/**
 * Builds a route's {@link UpstreamHandle}. Implemented by the server, which owns the proxy client;
 * called by {@link GatewayPipeline#prepare} for every route with an upstream, before the route's
 * generation is published. May throw to reject the generation (a timeout the client cannot
 * represent, say); the pipeline then stops whatever it already started for that generation.
 */
@FunctionalInterface
public interface UpstreamConnector
{
    UpstreamHandle connect(DefaultGatewayRoute route);
}
