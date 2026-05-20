package com.ethlo.r7.util;

import java.util.List;

import com.ethlo.r7.validation.ValidationResult;

public class ValidatorUtils
{
    private final ValidationResult result;

    public ValidatorUtils(final ValidationResult result)
    {
        this.result = result;
    }

    public ValidatorUtils required(String property, Object value)
    {
        if (value == null || value instanceof String s && s.isBlank())
        {
            result.addError(property, "'" + property + "' is required");
        }
        return this;
    }

    public ValidationResult results()
    {
        return result;
    }

    public void notEmpty(String property, List<?> value)
    {
        if (value == null || value.isEmpty())
        {
            result.addError(property, "'" + property + "' cannot be an empty list");
        }
    }

    public void invalid(String property, Object value, String message)
    {
        result.addError(property, "Invalid value " + value + " for property " + property + ": " + message);
    }
}
