package com.ethlo.r7.config;

import java.util.Map;
import java.util.TreeMap;

import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public record JournalDirectionDefinition(JournalLevel level,
                                         Map<String, JournalLevel> statusOverrides) implements ValidatableConfig
{
    public JournalDirectionDefinition(final JournalLevel level, final Map<String, JournalLevel> statusOverrides)
    {
        this.level = level != null ? level : JournalLevel.NONE;
        this.statusOverrides = statusOverrides != null ? statusOverrides : new TreeMap<>();
    }

    @Override
    public void validate(final ValidationResult result)
    {
        // Keys are checked here, not left to JournalOverrideParser at instantiation: there a bad
        // key surfaced as a bare NumberFormatException/ArrayIndexOutOfBounds stack trace at
        // startup, and as an opaque failure on hot reload, with nothing naming the route.
        for (final String key : this.statusOverrides.keySet())
        {
            try
            {
                JournalOverrideParser.parseKey(key);
            }
            catch (final IllegalArgumentException e)
            {
                result.addError("status_overrides", e.getMessage());
            }
        }
    }
}
