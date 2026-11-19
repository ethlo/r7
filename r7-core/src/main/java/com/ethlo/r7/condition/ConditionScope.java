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
    ROUTE_MATCH("when a route is chosen", false, Draw.ROUTING),

    /**
     * A journal {@code on_request} rule: the route is chosen and its filters have not run, so
     * only the client's request exists.
     */
    ON_REQUEST("when on_request rules are checked", false, Draw.JOURNAL),

    /**
     * A journal {@code on_response} rule: the response head is final, after the response
     * filters, so the client's response exists, and the upstream's if one answered.
     */
    ON_RESPONSE("when on_response rules are checked", true, Draw.JOURNAL),

    /**
     * An {@code unrouted} journal {@code on_request} rule: the request was refused before
     * routing, so only the client's request exists, and on every refusal but a route miss r7
     * never interpreted it.
     */
    UNROUTED("when unrouted rules are checked", false, Draw.JOURNAL);

    private final String when;
    private final boolean responses;
    private final Draw draw;

    ConditionScope(final String when, final boolean responses, final Draw draw)
    {
        this.when = when;
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

    public boolean allowsResponses()
    {
        return this.responses;
    }
}
