package com.ethlo.r7.config;

import java.util.Map;

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
        @Description("Journal levels for requests refused before routing. FULL is not allowed: a refused request's body is never read, and after a bad Transfer-Encoding its boundaries are not known.")
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
        validateDirection(journalResult.nested("request"), this.journal.request());
        validateDirection(journalResult.nested("response"), this.journal.response());
    }

    private static void validateDirection(final ValidationResult result, final JournalDirectionDefinition direction)
    {
        if (direction == null)
        {
            return;
        }
        if (direction.level() == JournalLevel.FULL)
        {
            result.addError("level", "FULL is not allowed for unrouted requests; use NONE, METADATA or HEADERS");
        }
        for (final Map.Entry<String, JournalLevel> entry : direction.statusOverrides().entrySet())
        {
            if (entry.getValue() == JournalLevel.FULL)
            {
                result.addError("status_overrides", "FULL is not allowed for unrouted requests (" + entry.getKey() + "); use NONE, METADATA or HEADERS");
            }
        }
    }

    /**
     * Either direction may be left out; an absent one journals nothing.
     */
    public JournalDefinition normalizedJournal()
    {
        final JournalDirectionDefinition none = new JournalDirectionDefinition(JournalLevel.NONE, null);
        return new JournalDefinition(
                this.journal.request() != null ? this.journal.request() : none,
                this.journal.response() != null ? this.journal.response() : none);
    }
}
