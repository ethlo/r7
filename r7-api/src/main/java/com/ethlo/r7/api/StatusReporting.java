package com.ethlo.r7.api;

/**
 * A filter, or any other component of a route, that can say how it is doing: a circuit breaker
 * that is open, a rate limiter that is rejecting. The management port shows it on the dashboard,
 * in its JSON and as Prometheus metrics.
 * <p>
 * {@link #status()} is called from the management snapshot's own timer, every few seconds and
 * never on a request. It must not block: it runs on the scheduler that also drives upstream health
 * checks and configuration reloads. Read the state the component already keeps; do not compute
 * anything expensive. A {@code status()} that throws is reported as {@link ComponentStatus.Health#ERROR}
 * with the exception as its detail.
 */
public interface StatusReporting
{
    /**
     * @return the component's status now, or null for nothing to report
     */
    ComponentStatus status();
}
