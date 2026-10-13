package com.ethlo.r7.api;

/**
 * Tests one request: a route's {@code match}, a journal rule's condition. Which request it is
 * given, the client's as sent or the one forwarded upstream, is decided by the {@code leg} the
 * configuration names, never by the predicate.
 * <p>
 * A predicate is a function of what it is given. It has no side effects and gives the same
 * answer for the same request however often, in whatever order, and whether or not it runs at
 * all: r7 evaluates predicates wherever a condition is checked, and skips them when a condition
 * short-circuits.
 */
public interface GatewayPredicate extends ShortInfo
{
    /**
     * Tests the given request against the predicate criteria.
     *
     * @param request the request to evaluate
     * @return true if the request matches, false otherwise
     */
    boolean test(GatewayRequest request);
}
