package com.ethlo.venturi.plugin;

import com.ethlo.venturi.validation.ValidationResult;

public class ValidatorUtils
{
    private final ValidationResult result;

    public ValidatorUtils(final ValidationResult result)
    {
        this.result = result;
    }

    public ValidatorUtils required(String context, String name, Object value)
    {
        if (value == null || value instanceof String s && s.isBlank())
        {
            result.addError(name, "'" + name + "' is required");
        }
        return this;
    }

    public ValidationResult results()
    {
        return result;
    }
}
