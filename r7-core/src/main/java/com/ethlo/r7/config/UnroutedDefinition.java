package com.ethlo.r7.config;

import com.ethlo.r7.doc.Description;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

/**
 * Journaling for requests refused before any route is chosen: no route matched, an ambiguous
 * path, a non-canonical Transfer-Encoding, TRACE, or a route pattern that exhausted its regex
 * budget. Without this section such requests leave no journal entry, and they are mostly what
 * scanners and probes send.
 */
public record UnroutedDefinition(
        @Description("Journal levels and on_request rules for requests refused before routing. FULL is not allowed: a refused request's body is never read, and after a bad Transfer-Encoding its boundaries are not known.")
        JournalDefinition journal
) implements ValidatableConfig
{
    /**
     * The route ID these requests are journaled under, in {@code gateway.route.id}. Reserved:
     * a configured route may not use it ({@link RouteDefinition#validate}), so tailers can rely
     * on it to identify refusals before routing.
     */
    public static final String ROUTE_ID = "<unrouted>";

    @Override
    public void validate(final ValidationResult result)
    {
        if (this.journal == null)
        {
            result.addError("journal", "is required: it is the only setting in the unrouted section");
            return;
        }
        final ValidationResult journalResult = result.nested("journal");
        this.journal.validate(journalResult);
        validateDirection(journalResult.nested("request"), this.journal.request());
        validateDirection(journalResult.nested("response"), this.journal.response());
        if (!this.journal.onResponse().isEmpty())
        {
            journalResult.addError("on_response", "r7's answer to a refused request is fixed by its reason; use on_request.");
        }
        for (int i = 0; i < this.journal.onRequest().size(); i++)
        {
            final JournalRuleDefinition rule = this.journal.onRequest().get(i);
            if (rule == null)
            {
                // Already reported as empty by the journal's own validation.
                continue;
            }
            final ValidationResult ruleResult = journalResult.nested("on_request[" + i + "]");
            validateDirection(ruleResult.nested("request"), rule.request());
            validateDirection(ruleResult.nested("response"), rule.response());
        }
    }

    private static void validateDirection(final ValidationResult result, final JournalDirectionDefinition direction)
    {
        if (direction != null && direction.level() == JournalLevel.FULL)
        {
            result.addError("level", "FULL is not allowed for unrouted requests; use NONE, METADATA or HEADERS");
        }
        if (direction != null && direction.truncateBodyAt() != null)
        {
            result.addError("truncate_body_at", "applies only at FULL, which unrouted requests are never journaled at.");
        }
    }
}
