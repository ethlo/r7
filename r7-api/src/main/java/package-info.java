/**
 * Core API for the R7 high-performance API Gateway.
 * <p>
 * This package defines the contracts for request interception, routing,
 * and lifecycle management. The architecture is
 * built on a "State over Stages" philosophy, where a central
 * {@link com.ethlo.r7.api.GatewayExchange} carries all metadata
 * throughout the request's journey.
 * * <h2>Core Concepts</h2>
 * <ul>
 * <li><b>The Exchange:</b> The {@link com.ethlo.r7.api.GatewayExchange}
 * acts as the single source of truth, providing access to the
 * {@link com.ethlo.r7.api.GatewayRequest} and
 * {@link com.ethlo.r7.api.GatewayResponse}.</li>
 * <li><b>Linear Pipeline:</b> Filters are executed in a strict sequential
 * order based on the phase (Client Request, Upstream Request, Client Response,
 * and Completed).</li>
 * <li><b>Short-Circuiting:</b> Any request-phase filter can terminate the
 * pipeline early by calling {@code terminate()} or {@code shortCircuit()},
 * skipping the proxy step and moving directly to the response phase.</li>
 * </ul>
 * * <h2>Threading Model</h2>
 * By default, filters execute on the server's primary non-blocking I/O loop
 * to maximize throughput. Filters that
 * perform blocking I/O must explicitly override {@code requiresDispatch()}
 * to be offloaded to a worker thread pool.
 *
 *
 */
package com.ethlo.r7.api;