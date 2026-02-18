package com.ethlo.venturi.core.filters;

import com.ethlo.venturi.validation.Validatable;
import com.ethlo.venturi.validation.ValidationResult;

public record StripPathPrefixConfig(Integer parts) implements Validatable
{
    @Override
    public void validate(ValidationResult result)
    {
        if (parts == null)
        {
            result.addError("filters.StripPrefix.parts", "is required");
        }
        else if (parts < 1)
        {
            result.addError("filters.StripPrefix.parts", "must be greater than 0");
        }
    }
}