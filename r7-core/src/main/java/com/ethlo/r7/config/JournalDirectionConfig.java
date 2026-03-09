package com.ethlo.r7.config;

import com.ethlo.r7.journal.api.JournalLevel;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record JournalDirectionConfig(
        JournalLevel level,
        JournalLevel[] statusOverrides
)
{
    /**
     * Intercepts the Jackson deserialization to convert the YAML map into the $O(1)$ array.
     */
    @JsonCreator
    public static JournalDirectionConfig create(
            @JsonProperty("level") final JournalLevel level,
            @JsonProperty("status_overrides") final Map<String, String> rawOverrides)
    {
        // Default to NONE if omitted, and run the map through our boot-time parser
        return new JournalDirectionConfig(
                level != null ? level : JournalLevel.NONE,
                JournalOverrideParser.parseOverrides(rawOverrides)
        );
    }

    /**
     * Resolves the final logging level for this specific direction.
     */
    public JournalLevel resolve(final int statusCode)
    {
        if (statusOverrides != null && statusCode >= 100 && statusCode < 600)
        {
            final JournalLevel override = statusOverrides[statusCode];
            if (override != null)
            {
                return override;
            }
        }
        return level;
    }
}