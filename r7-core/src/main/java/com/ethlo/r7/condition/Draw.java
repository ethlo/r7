package com.ethlo.r7.condition;

/**
 * Which of an exchange's draws a {@code Sample} tests. Each decision has its own: on a shared
 * draw, a journal sample beside a {@code Sample} canary would record exactly the canary's traffic
 * and none of the rest. Within one decision the draw is shared, so its {@code Sample}s agree.
 */
public enum Draw
{
    /**
     * Route and group matches.
     */
    ROUTING,

    /**
     * Journal rules: {@code on_request} and {@code on_response} test one draw, so a sampled
     * exchange is recorded in both directions.
     */
    JOURNAL
}
