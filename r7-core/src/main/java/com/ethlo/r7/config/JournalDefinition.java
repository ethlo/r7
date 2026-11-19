package com.ethlo.r7.config;

import java.util.List;
import java.util.Optional;

import com.ethlo.r7.doc.Description;
import com.ethlo.r7.doc.Nullable;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

/**
 * A {@code journal} block, the same shape at the root, on a group and on a route. Each direction
 * key is inherited on its own from the innermost level naming it; the rules of every level apply,
 * the route's checked first, then its group's, then the root's.
 */
public record JournalDefinition(
        @Description("Base levels for the request direction.")
        @Nullable JournalDirectionDefinition request,
        @Description("Base levels for the response direction.")
        @Nullable JournalDirectionDefinition response,
        @Description("Rules checked once the route is chosen, before any route filter runs and before the request body streams.")
        @Nullable List<JournalRuleDefinition> onRequest,
        @Description("Rules checked once the response head is final, after the response filters and just before it is written to the client.")
        @Nullable List<JournalRuleDefinition> onResponse
) implements ValidatableConfig
{
    @Override
    public List<JournalRuleDefinition> onRequest()
    {
        return Optional.ofNullable(this.onRequest).orElse(List.of());
    }

    @Override
    public List<JournalRuleDefinition> onResponse()
    {
        return Optional.ofNullable(this.onResponse).orElse(List.of());
    }

    @Override
    public void validate(final ValidationResult result)
    {
        if (this.request != null)
        {
            this.request.validate(result.nested("request"));
        }
        if (this.response != null)
        {
            this.response.validate(result.nested("response"));
        }
        final List<JournalRuleDefinition> onRequest = this.onRequest();
        for (int i = 0; i < onRequest.size(); i++)
        {
            validateRule(onRequest.get(i), result.nested("on_request[" + i + "]"), false);
        }
        final List<JournalRuleDefinition> onResponse = this.onResponse();
        for (int i = 0; i < onResponse.size(); i++)
        {
            validateRule(onResponse.get(i), result.nested("on_response[" + i + "]"), true);
        }
    }

    private static void validateRule(final JournalRuleDefinition rule, final ValidationResult result, final boolean onResponse)
    {
        if (rule == null)
        {
            result.addError(null, "is empty: a rule needs match and a level");
            return;
        }
        rule.validate(result, onResponse);
    }
}
