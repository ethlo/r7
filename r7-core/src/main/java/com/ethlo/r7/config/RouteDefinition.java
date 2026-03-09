package com.ethlo.r7.config;

import java.util.List;
import java.util.Optional;

import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public record RouteDefinition(CharSequence id, UpstreamConfig upstream, ConditionDefinition match,
                              RouteJournalConfig journal,
                              List<FilterDefinition> filters) implements ValidatableConfig
{
    @Override
    public void validate(final ValidationResult result)
    {
        final ValidatorUtils validator = new ValidatorUtils(result);
        validator.required("route", "id", "Route ID must not be empty");

        if (upstream != null)
        {
            upstream.validate(result);
        }

        if (journal != null)
        {
            journal.validate(result);
        }
    }

    public RouteJournalConfig journal()
    {
        return Optional.ofNullable(journal).orElse(new RouteJournalConfig(new JournalDirectionConfig(JournalLevel.NONE, null), new JournalDirectionConfig(JournalLevel.NONE, null)));
    }
}