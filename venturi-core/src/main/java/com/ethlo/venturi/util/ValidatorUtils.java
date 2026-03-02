package com.ethlo.venturi.util;

import com.ethlo.venturi.validation.ValidationResult;

import java.util.List;

public class ValidatorUtils
{
    private final ValidationResult result;

    public ValidatorUtils(final ValidationResult result)
    {
        this.result = result;
    }

    public ValidatorUtils required(String context, String property, Object value)
    {
        if (value == null || value instanceof String s && s.isBlank())
        {
            result.addError(context, "'" + property + "' is required");
        }
        return this;
    }

    public ValidationResult results()
    {
        return result;
    }

    public void notEmpty(String context, String property, List<?> value)
    {
        if (value == null ||value.isEmpty())
        {
            result.addError(context, "'" + property + "' cannot be an empty list");
        }
    }

    public void invalid(String context, String property, String value, String message)
    {
        result.addError(context, "Invalid value " + value + "for property " + property + ": " + message);
    }
}
