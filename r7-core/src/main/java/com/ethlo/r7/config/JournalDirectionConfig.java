package com.ethlo.r7.config;

import java.util.Arrays;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.ethlo.r7.journal.api.JournalLevel;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record JournalDirectionConfig(
        JournalLevel level,
        JournalLevel[] statusOverrides
)
{
    public static final int HIGHEST_STATUS_CODE = 599;
    public static final int LOWEST_STATUS_CODE = 100;

    public JournalDirectionConfig(final JournalLevel level, final JournalLevel[] statusOverrides)
    {
        this.level = level != null ? level : JournalLevel.NONE;
        if (statusOverrides != null)
        {
            this.statusOverrides = Arrays.copyOf(statusOverrides, statusOverrides.length);
        }
        else
        {
            this.statusOverrides = new JournalLevel[HIGHEST_STATUS_CODE + 1];
        }
    }

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

    @Override
    public String toString()
    {
        final StringJoiner joiner = new StringJoiner(", ", JournalDirectionConfig.class.getSimpleName() + "[", "]");
        joiner.add("level=" + level);

        final String overridesMap = IntStream.rangeClosed(LOWEST_STATUS_CODE, HIGHEST_STATUS_CODE)
                .filter(i -> statusOverrides[i] != null)
                .boxed()
                .collect(Collectors.toMap(
                        i -> i,
                        i -> statusOverrides[i]
                ))
                .toString();

        joiner.add("statusOverrides=" + overridesMap);

        return joiner.toString();
    }

    /**
     * Resolves the final logging level for this specific direction.
     */
    public JournalLevel resolve(final int statusCode)
    {
        if (statusOverrides != null && statusCode >= LOWEST_STATUS_CODE && statusCode < HIGHEST_STATUS_CODE)
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