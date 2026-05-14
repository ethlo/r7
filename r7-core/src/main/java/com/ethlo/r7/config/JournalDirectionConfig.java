package com.ethlo.r7.config;

import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.ethlo.r7.journal.api.JournalLevel;

public record JournalDirectionConfig(JournalLevel level, JournalLevel[] statusOverrides)
{
    public static final int HIGHEST_STATUS_CODE = 599;
    public static final int LOWEST_STATUS_CODE = 100;

    public JournalDirectionConfig(final JournalLevel level, final JournalLevel[] statusOverrides)
    {
        this.level = level != null ? level : JournalLevel.NONE;
        this.statusOverrides = Objects.requireNonNullElseGet(statusOverrides, () -> new JournalLevel[HIGHEST_STATUS_CODE + 1]);
    }

    @Override
    public String toString()
    {
        final StringJoiner joiner = new StringJoiner(", ", JournalDirectionConfig.class.getSimpleName() + "[", "]");
        joiner.add("level=" + level);
        final String overridesMap = statusOverrideMap().toString();
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

    public Map<Integer, JournalLevel> statusOverrideMap()
    {
        return IntStream.rangeClosed(LOWEST_STATUS_CODE, HIGHEST_STATUS_CODE)
                .filter(i -> statusOverrides[i] != null)
                .boxed()
                .collect(Collectors.toMap(
                        i -> i,
                        i -> statusOverrides[i], (a, b) -> a, TreeMap::new
                ));
    }
}