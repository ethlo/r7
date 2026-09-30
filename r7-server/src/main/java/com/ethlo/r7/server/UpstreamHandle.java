package com.ethlo.r7.server;

import com.ethlo.r7.status.UpstreamTargetObserver;

/**
 * A route's upstream as the server proxies to it: built once per route when a generation of routes
 * is prepared, told by the health monitor (or, without one, once at build time) which targets are
 * up, and handed back to the server by {@link ServerExchange#proxy(UpstreamHandle)} for every
 * request the route sends upstream. What it holds - a connection pool, a load balancer - is the
 * server's.
 */
public interface UpstreamHandle extends UpstreamTargetObserver
{
}
