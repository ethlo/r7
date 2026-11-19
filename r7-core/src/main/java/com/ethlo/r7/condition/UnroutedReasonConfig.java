package com.ethlo.r7.condition;

import java.util.List;

import com.ethlo.r7.doc.Description;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

/**
 * The parameters of {@code UnroutedReason}, a node of the condition tree rather than a
 * predicate: it reads why the request was refused, not the request, and so has no {@code leg}.
 */
@Description("Matches requests refused before routing for one of these reasons. Only under unrouted.")
public record UnroutedReasonConfig(
        @Description("The reasons to match: no_route (404), trace (501), ambiguous_path (400), transfer_encoding (400), regex_budget (500).")
        List<String> include) implements ValidatableConfig
{
    @Override
    public void validate(final ValidationResult result)
    {
        if (this.include == null || this.include.isEmpty())
        {
            result.addError("include", "Must name at least one reason: " + String.join(", ", UnroutedReason.configNames()));
            return;
        }
        for (int i = 0; i < this.include.size(); i++)
        {
            final String name = this.include.get(i);
            if (UnroutedReason.fromConfig(name) == null)
            {
                result.addError("include[" + i + "]", "Unknown reason '" + name + "'. Must be one of: " + String.join(", ", UnroutedReason.configNames()));
            }
        }
    }
}
