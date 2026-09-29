package com.ethlo.r7.config;

import static com.ethlo.r7.config.JournalDirectionConfig.HIGHEST_STATUS_CODE;
import static com.ethlo.r7.config.JournalDirectionConfig.LOWEST_STATUS_CODE;

import java.util.Map;

import com.ethlo.r7.journal.api.JournalLevel;

public final class JournalOverrideParser
{
    public static final String ACCEPTED_FORMS = "Accepted forms: a status class 'Nxx' ('1xx' to '5xx'), a single status code "
            + LOWEST_STATUS_CODE + "-" + HIGHEST_STATUS_CODE + " (e.g. '429'), or a comma-separated list of such codes (e.g. '401,403')";

    private JournalOverrideParser()
    {
    }

    public static JournalLevel[] parseOverrides(final Map<String, JournalLevel> rawOverrides)
    {
        if (rawOverrides == null || rawOverrides.isEmpty())
        {
            return null;
        }

        final JournalLevel[] expanded = new JournalLevel[HIGHEST_STATUS_CODE + 1];

        for (final Map.Entry<String, JournalLevel> entry : rawOverrides.entrySet())
        {
            for (final int code : parseKey(entry.getKey()))
            {
                expanded[code] = entry.getValue();
            }
        }

        return expanded;
    }

    /**
     * Expands one {@code status_overrides} key into the status codes it covers.
     * <p>
     * This is the single definition of what a key may look like: {@link JournalDirectionDefinition#validate}
     * calls it to report a bad key against its config path, and {@link #parseOverrides} calls it again when
     * building the lookup table, so validation can never accept a key that parsing then chokes on.
     *
     * @throws IllegalArgumentException naming the key and the accepted forms
     */
    public static int[] parseKey(final String rawKey)
    {
        if (rawKey == null)
        {
            throw invalid(null);
        }

        final String key = rawKey.trim().toLowerCase();

        if (key.length() == 3 && key.endsWith("xx"))
        {
            // Status classes like "4xx" or "5xx"
            final int family = key.charAt(0) - '0';
            if (family < LOWEST_STATUS_CODE / 100 || family > HIGHEST_STATUS_CODE / 100)
            {
                throw invalid(rawKey);
            }
            final int[] codes = new int[100];
            for (int i = 0; i < 100; i++)
            {
                codes[i] = family * 100 + i;
            }
            return codes;
        }

        // A single code like "429", or a comma-separated list like "401,403"
        final String[] parts = key.split(",", -1);
        final int[] codes = new int[parts.length];
        for (int i = 0; i < parts.length; i++)
        {
            codes[i] = parseCode(parts[i].trim(), rawKey);
        }
        return codes;
    }

    private static int parseCode(final String code, final String rawKey)
    {
        // Exactly three digits: rejects signs, ranges such as "500-599", and anything
        // Integer.parseInt would otherwise accept or turn into an opaque exception.
        if (code.length() != 3)
        {
            throw invalid(rawKey);
        }
        for (int i = 0; i < 3; i++)
        {
            final char c = code.charAt(i);
            if (c < '0' || c > '9')
            {
                throw invalid(rawKey);
            }
        }

        final int value = Integer.parseInt(code);
        if (value < LOWEST_STATUS_CODE || value > HIGHEST_STATUS_CODE)
        {
            throw invalid(rawKey);
        }
        return value;
    }

    private static IllegalArgumentException invalid(final String rawKey)
    {
        return new IllegalArgumentException("Invalid status override key '" + rawKey + "'. " + ACCEPTED_FORMS);
    }
}
