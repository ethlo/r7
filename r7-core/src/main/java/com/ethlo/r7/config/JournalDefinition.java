package com.ethlo.r7.config;

import java.util.Optional;

import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.validation.ValidatableConfig;

/**
 * Either direction may be left out, and journals nothing of its own. Without these defaults a
 * journal section naming only {@code request:} failed to load with a NullPointerException.
 */
public record JournalDefinition(JournalDirectionDefinition request,
                                JournalDirectionDefinition response) implements ValidatableConfig
{
    @Override
    public JournalDirectionDefinition request()
    {
        return Optional.ofNullable(this.request).orElseGet(() -> new JournalDirectionDefinition(JournalLevel.NONE, null));
    }

    @Override
    public JournalDirectionDefinition response()
    {
        return Optional.ofNullable(this.response).orElseGet(() -> new JournalDirectionDefinition(JournalLevel.NONE, null));
    }
}
