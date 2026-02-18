package com.ethlo.venturi.config;

import java.util.Map;

import com.ethlo.venturi.validation.Validatable;
import com.ethlo.venturi.validation.ValidationResult;

public record FilterDefinition(String type, Map<String, String> args) implements Validatable
{
    @Override
    public void validate(final ValidationResult result)
    {
        if (type.isEmpty())
        {
            result.addError("type", "type is required for filter: " + args);
        }
    }
}
