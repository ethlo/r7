package com.ethlo.r7.config;

import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public record JournalDefinition(JournalDirectionDefinition request,
                                JournalDirectionDefinition response) implements ValidatableConfig
{
    public JournalDefinition(final JournalDirectionDefinition request, final JournalDirectionDefinition response)
    {
        // A journal block may configure only one direction; the other must not stay null, or
        // ConfigurationManager.createJournalConfig fails with an NPE at startup.
        this.request = request != null ? request : new JournalDirectionDefinition(JournalLevel.NONE, null);
        this.response = response != null ? response : new JournalDirectionDefinition(JournalLevel.NONE, null);
    }

    @Override
    public void validate(final ValidationResult result)
    {
        this.request.validate(result.nested("request"));
        this.response.validate(result.nested("response"));
    }
}
