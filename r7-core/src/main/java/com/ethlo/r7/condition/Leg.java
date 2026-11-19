package com.ethlo.r7.condition;

/**
 * Which of an exchange's two requests, or two responses, a predicate tests.
 */
public enum Leg
{
    /**
     * The request as the client sent it, before any filter; the response as the client
     * receives it, after the response filters.
     */
    CLIENT("client"),

    /**
     * The request as forwarded, after the upstream-phase filters; the response as the upstream
     * sent it, before the response filters.
     */
    UPSTREAM("upstream");

    private final String configName;

    Leg(final String configName)
    {
        this.configName = configName;
    }

    /**
     * @return the value of {@code leg} in the configuration
     */
    public String configName()
    {
        return this.configName;
    }

    /**
     * @return the leg named {@code value} in the configuration, or {@code null} for none
     */
    public static Leg fromConfig(final String value)
    {
        for (final Leg leg : values())
        {
            if (leg.configName.equals(value))
            {
                return leg;
            }
        }
        return null;
    }
}
