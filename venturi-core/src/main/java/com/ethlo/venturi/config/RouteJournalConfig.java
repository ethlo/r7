package com.ethlo.venturi.config;

import com.ethlo.venturi.journal.api.JournalLevel;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;

public class RouteJournalConfig implements ValidatableConfig
{
    public JournalLevel request = JournalLevel.NONE;
    public JournalLevel response = JournalLevel.NONE;

    @Override
    public void validate(ValidationResult result)
    {
        // TODO: Implement me
    }
}