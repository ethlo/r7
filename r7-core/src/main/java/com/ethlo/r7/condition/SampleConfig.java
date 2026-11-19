package com.ethlo.r7.condition;

import com.ethlo.r7.doc.Description;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

/**
 * The parameters of {@code Sample}, a node of the condition tree rather than a predicate: it
 * reads the exchange's draw, not a request or a response, and so has no {@code leg}.
 */
@Description("Matches a share of exchanges, drawn once per exchange from a random source.")
public record SampleConfig(
        @Description("The share of exchanges to match: greater than 0, at most 1 (0.01 is one in a hundred).")
        Double rate) implements ValidatableConfig
{
    @Override
    public void validate(final ValidationResult result)
    {
        if (this.rate == null)
        {
            result.addError("rate", "Required");
        }
        else if (!(this.rate > 0 && this.rate <= 1))
        {
            result.addError("rate", "Must be greater than 0 and at most 1, received " + this.rate);
        }
    }
}
