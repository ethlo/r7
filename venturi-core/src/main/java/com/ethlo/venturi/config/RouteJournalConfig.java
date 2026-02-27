package com.ethlo.venturi.config;

import com.ethlo.venturi.journal.api.JournalLevel;
import com.ethlo.venturi.util.ValidatorUtils;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;

public record RouteJournalConfig(JournalLevel request, JournalLevel response) implements ValidatableConfig
{
    @Override
    public void validate(ValidationResult result)
    {
        new ValidatorUtils(result)
                .required("journal", "request", request)
                .required("journal", "response", response);
    }
}