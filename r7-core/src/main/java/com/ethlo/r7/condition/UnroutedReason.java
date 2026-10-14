package com.ethlo.r7.condition;

import java.util.Arrays;
import java.util.List;

/**
 * Why a request was refused before routing, as {@code unrouted} rules and
 * {@code gateway.unrouted.reason} name it.
 */
public enum UnroutedReason
{
    /**
     * No route matched: {@code 404}. The one refusal whose request passed every guard routing
     * relies on.
     */
    NO_ROUTE("no_route"),

    /**
     * {@code TRACE}: {@code 501}.
     */
    TRACE("trace"),

    /**
     * A path the upstream could resolve differently: {@code 400}.
     */
    AMBIGUOUS_PATH("ambiguous_path"),

    /**
     * A non-canonical {@code Transfer-Encoding}: {@code 400}.
     */
    TRANSFER_ENCODING("transfer_encoding"),

    /**
     * A route pattern exhausted its regex budget: {@code 500}.
     */
    REGEX_BUDGET("regex_budget");

    private final String configName;

    UnroutedReason(final String configName)
    {
        this.configName = configName;
    }

    /**
     * @return the name in the configuration and the journal: {@code no_route}
     */
    public String configName()
    {
        return this.configName;
    }

    /**
     * @return the reason with that name, or {@code null} for none
     */
    public static UnroutedReason fromConfig(final String name)
    {
        for (final UnroutedReason reason : values())
        {
            if (reason.configName.equals(name))
            {
                return reason;
            }
        }
        return null;
    }

    public static List<String> configNames()
    {
        return Arrays.stream(values()).map(UnroutedReason::configName).toList();
    }
}
