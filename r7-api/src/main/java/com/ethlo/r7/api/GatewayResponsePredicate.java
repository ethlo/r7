package com.ethlo.r7.api;

/**
 * Tests one response. Which response it is given, the one the client receives or the one the
 * upstream sent, is decided by the {@code leg} the configuration names, never by the predicate.
 * <p>
 * The contract of {@link GatewayPredicate} holds: a response predicate is a function of what it
 * is given, with no side effects, and gives the same answer however often it runs.
 */
public interface GatewayResponsePredicate extends ShortInfo
{
    /**
     * @param response the response to evaluate
     * @return true if the response matches, false otherwise
     */
    boolean test(GatewayResponse response);
}
