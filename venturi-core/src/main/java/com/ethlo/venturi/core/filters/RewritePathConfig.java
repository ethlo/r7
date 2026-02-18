package com.ethlo.venturi.core.filters;

import java.util.regex.Pattern;

import com.ethlo.venturi.validation.Validatable;
import com.ethlo.venturi.validation.ValidationResult;

public record RewritePathConfig(String regexp, String replacement) implements Validatable
{
    @Override
    public void validate(ValidationResult result)
    {
        if (regexp == null || regexp.isBlank())
        {
            result.addError("regexp", "must not be empty");
        }
        if (replacement == null)
        {
            result.addError("replacement", "must not be null");
        }

        if (regexp != null && !regexp.isBlank())
        {
            try
            {
                Pattern.compile(regexp);
            }
            catch (Exception e)
            {
                result.addError("regexp", "Invalid regex: " + e.getMessage());
            }
        }
    }
}