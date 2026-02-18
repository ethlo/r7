package com.ethlo.venturi.validation;

import java.util.ArrayList;
import java.util.List;

public final class ValidationResult
{
    private final List<String> errors = new ArrayList<>();

    public void addError(String context, String message)
    {
        errors.add("[%s] %s".formatted(context, message));
    }

    public boolean hasErrors()
    {
        return !errors.isEmpty();
    }

    public void throwIfInvalid()
    {
        if (hasErrors())
        {
            final String report = String.join("\n - ", errors);
            throw new IllegalStateException("Gateway configuration is invalid:\n - " + report);
        }
    }
}