package com.ethlo.r7.config;

import java.util.Map;
import java.util.TreeMap;

import com.ethlo.r7.journal.api.JournalLevel;

public record JournalDirectionDefinition(JournalLevel level, Map<String, JournalLevel> statusOverrides)
{
    public JournalDirectionDefinition(final JournalLevel level, final Map<String, JournalLevel> statusOverrides)
    {
        this.level = level != null ? level : JournalLevel.NONE;
        this.statusOverrides = statusOverrides != null ? statusOverrides : new TreeMap<>();
    }
}