package com.ethlo.r7.validation;

import java.util.ArrayList;
import java.util.List;

public final class ValidationResult
{
    private final List<String> errors;
    private final String pathPrefix;

    // Root constructor used at the start of validation
    public ValidationResult()
    {
        this.errors = new ArrayList<>();
        this.pathPrefix = "";
    }

    // Private constructor for nested contexts.
    // Crucially, it shares the exact same ArrayList reference.
    private ValidationResult(final List<String> errors, final String pathPrefix)
    {
        this.errors = errors;
        this.pathPrefix = pathPrefix;
    }

    /**
     * Creates a scoped view of this result. All errors reported to the returned
     * instance will automatically be prefixed with the combined path.
     */
    public ValidationResult nested(final String segment)
    {
        final String newPrefix = this.pathPrefix.isEmpty() ? segment : this.pathPrefix + "." + segment;
        return new ValidationResult(this.errors, newPrefix);
    }

    public void addError(final String field, final String message)
    {
        final String fullPath;

        if (field == null || field.isBlank())
        {
            fullPath = this.pathPrefix.isEmpty() ? "root" : this.pathPrefix;
        }
        else if (this.pathPrefix.isEmpty())
        {
            fullPath = field;
        }
        else
        {
            fullPath = this.pathPrefix + "." + field;
        }

        this.errors.add("[%s] %s".formatted(fullPath, message));
    }

    public boolean hasErrors()
    {
        return !this.errors.isEmpty();
    }

    public void throwIfInvalid()
    {
        if (this.hasErrors())
        {
            final String report = String.join("\n - ", this.errors);
            throw new IllegalStateException("Gateway configuration is invalid:\n - " + report);
        }
    }

    public List<String> getErrors()
    {
        return this.errors;
    }
}