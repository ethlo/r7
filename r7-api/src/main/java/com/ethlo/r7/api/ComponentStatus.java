package com.ethlo.r7.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a {@link StatusReporting} component reports.
 *
 * @param health how it is doing
 * @param detail a short line for a person, or null: shown on the dashboard, never a metric
 * @param values numbers worth graphing, by name, in the order given. Names are lower-case
 *               {@code snake_case}; each becomes a Prometheus series, so a name must not carry data
 *               (a client address, a path) or the number of series grows without bound.
 */
public record ComponentStatus(Health health, String detail, Map<String, Long> values)
{
    private static final Pattern VALUE_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    /**
     * Declared from best to worst: the gateway's overall health is the greatest a component reports.
     */
    public enum Health
    {
        OK, WARN, ERROR
    }

    public ComponentStatus
    {
        Objects.requireNonNull(health, "health");
        if (values == null || values.isEmpty())
        {
            values = Map.of();
        }
        else
        {
            for (final Map.Entry<String, Long> entry : values.entrySet())
            {
                if (entry.getKey() == null || !VALUE_NAME.matcher(entry.getKey()).matches())
                {
                    throw new IllegalArgumentException("A status value name must be lower-case snake_case, at most 64 characters: " + entry.getKey());
                }
                Objects.requireNonNull(entry.getValue(), () -> "status value " + entry.getKey());
            }
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    public static ComponentStatus ok()
    {
        return new ComponentStatus(Health.OK, null, null);
    }
}
