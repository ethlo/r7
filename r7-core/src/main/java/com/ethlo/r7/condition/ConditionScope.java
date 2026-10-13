package com.ethlo.r7.condition;

/**
 * Where a condition is evaluated, and so what of the exchange exists when it is.
 */
public enum ConditionScope
{
    /**
     * A route's or group's {@code match}: the route is being chosen, and only the client's
     * request exists.
     */
    ROUTE_MATCH("when a route is chosen", false, false);

    private final String when;
    private final boolean upstreamLeg;
    private final boolean responses;

    ConditionScope(final String when, final boolean upstreamLeg, final boolean responses)
    {
        this.when = when;
        this.upstreamLeg = upstreamLeg;
        this.responses = responses;
    }

    /**
     * @return when the condition is evaluated, for messages: "when a route is chosen"
     */
    public String when()
    {
        return this.when;
    }

    public boolean allowsUpstreamLeg()
    {
        return this.upstreamLeg;
    }

    public boolean allowsResponses()
    {
        return this.responses;
    }
}
