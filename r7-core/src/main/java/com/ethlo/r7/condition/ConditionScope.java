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
    ROUTE_MATCH("when a route is chosen", false, false, Draw.ROUTING);

    private final String when;
    private final boolean upstreamLeg;
    private final boolean responses;
    private final Draw draw;

    ConditionScope(final String when, final boolean upstreamLeg, final boolean responses, final Draw draw)
    {
        this.when = when;
        this.upstreamLeg = upstreamLeg;
        this.responses = responses;
        this.draw = draw;
    }

    /**
     * @return the draw a {@code Sample} in this scope tests
     */
    public Draw draw()
    {
        return this.draw;
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
