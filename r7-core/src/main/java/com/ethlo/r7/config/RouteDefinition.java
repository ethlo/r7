package com.ethlo.r7.config;

import java.util.List;
import java.util.Optional;

import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public record RouteDefinition(String id,
                              UpstreamConfig upstream,
                              ConditionDefinition match,
                              JournalDefinition journal,
                              List<FilterDefinition> filters) implements ValidatableConfig
{
    @Override
    public void validate(final ValidationResult result)
    {
        final ValidatorUtils validator = new ValidatorUtils(result);
        validator.required("id", this.id());

        if (upstream != null)
        {
            upstream.validate(result);
        }

        if (journal != null)
        {
            journal.validate(result);
        }

        // A route must have a purpose
        if (this.match() == null && (this.filters() == null || this.filters().isEmpty()))
        {
            result.addError("id", "Route '" + this.id() + "' must define at least one match or one filter");
        }
    }

    @Override
    public JournalDefinition journal()
    {
        return Optional.ofNullable(journal).orElse(new JournalDefinition(new JournalDirectionDefinition(JournalLevel.NONE, null), new JournalDirectionDefinition(JournalLevel.NONE, null)));
    }
}