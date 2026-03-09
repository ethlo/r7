package com.ethlo.r7.config;

import java.util.Map;

import com.ethlo.r7.journal.api.JournalLevel;

public final class JournalOverrideParser
{
    public static JournalLevel[] parseOverrides(final Map<String, String> rawOverrides)
    {
        if (rawOverrides == null || rawOverrides.isEmpty())
        {
            return null;
        }

        final JournalLevel[] expanded = new JournalLevel[600];

        for (final Map.Entry<String, String> entry : rawOverrides.entrySet())
        {
            final String key = entry.getKey().trim().toLowerCase();
            final JournalLevel level = JournalLevel.valueOf(entry.getValue().toUpperCase());

            if (key.endsWith("xx"))
            {
                // Handle wildcards like "4xx" or "5xx"
                final int familyBase = (key.charAt(0) - '0') * 100;
                for (int i = familyBase; i < familyBase + 100; i++)
                {
                    expanded[i] = level;
                }
            }
            else if (key.contains(","))
            {
                // Handle comma-separated lists like "401,403"
                final String[] codes = key.split(",");
                for (final String code : codes)
                {
                    expanded[Integer.parseInt(code.trim())] = level;
                }
            }
            else
            {
                // Handle exact matches like "429"
                expanded[Integer.parseInt(key)] = level;
            }
        }

        return expanded;
    }
}