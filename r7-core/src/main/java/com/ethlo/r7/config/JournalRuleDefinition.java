package com.ethlo.r7.config;

import com.ethlo.r7.doc.Description;
import com.ethlo.r7.doc.Nullable;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidationResult;

/**
 * A journal rule: when {@code match} holds, the direction keys it names replace what the base
 * levels, or an earlier stage, resolved them to.
 */
public record JournalRuleDefinition(
        @Description("A label, unique among all rules in the file, journaled in gateway.journal.rule in place of the rule's path when it fires.")
        @Nullable String id,
        @Description("Predicates, as for a route's match: a list is AND, and/or/not nest.")
        ConditionDefinition match,
        @Description("The request keys to use when the rule matches: level, truncate_body_at, or both.")
        @Nullable JournalDirectionDefinition request,
        @Description("The response keys to use when the rule matches: level, truncate_body_at, or both.")
        @Nullable JournalDirectionDefinition response
)
{
    /**
     * @param onResponse whether the rule is in {@code on_response}, where the request body has
     *                   already streamed
     */
    public void validate(final ValidationResult result, final boolean onResponse)
    {
        if (this.id != null)
        {
            // Journaled as gateway.journal.rule.
            new ValidatorUtils(result).notBlank("id", this.id).journalText("id", this.id);
        }
        if (this.match == null)
        {
            result.addError(null, "match is required");
        }
        else
        {
            // Includes 'match: []': a rule has no fallback-only reading, and an empty condition
            // would build to one that always holds.
            this.match.validateTree(result);
        }
        if (this.request == null && this.response == null)
        {
            result.addError(null, "sets no level; name request, response or both");
        }
        if (this.request != null)
        {
            this.request.validate(result.nested("request"));
            if (onResponse && this.request.level() == JournalLevel.FULL)
            {
                result.addError("request", "FULL cannot be set in on_response: the request body has already streamed. Set it in on_request or as the base level.");
            }
            if (onResponse && this.request.truncateBodyAt() != null)
            {
                result.nested("request").addError("truncate_body_at", "the request body has already streamed; set it in on_request or as the base.");
            }
        }
        if (this.response != null)
        {
            this.response.validate(result.nested("response"));
        }
    }
}
