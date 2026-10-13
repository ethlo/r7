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
        // Journaled as gateway.route.id (and gateway.fallback.id) on every exchange.
        validator.journalText("id", this.id());
        // Reserved so the audit trail can tell requests refused before routing apart from a
        // route's own by gateway.route.id alone.
        if (UnroutedDefinition.ROUTE_ID.equals(this.id()))
        {
            result.addError("id", "'" + UnroutedDefinition.ROUTE_ID + "' is reserved for requests refused before routing");
        }

        if (upstream != null)
        {
            upstream.validate(result);
        }

        if (journal != null)
        {
            journal.validate(result.nested("journal"));
        }

        // A route without a match used to load and never match, silently, when it had filters:
        // a forgotten 'match' then looks like a dead route. Fallback-only is said explicitly.
        if (this.match() == null)
        {
            result.addError("match", "Route '" + this.id() + "' needs a match; write 'match: []' for a route reached only as a fallback");
        }
    }

    @Override
    public JournalDefinition journal()
    {
        return Optional.ofNullable(journal).orElse(new JournalDefinition(new JournalDirectionDefinition(JournalLevel.NONE, null), new JournalDirectionDefinition(JournalLevel.NONE, null)));
    }
}