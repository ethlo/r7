package com.ethlo.r7.util;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.ethlo.r7.config.model.DataSize;
import com.ethlo.r7.validation.ValidationResult;

public class ValidatorUtils
{
    private final ValidationResult result;

    public ValidatorUtils(final ValidationResult result)
    {
        this.result = result;
    }

    public ValidatorUtils ifValid(final Runnable block)
    {
        if (!this.result.hasErrors())
        {
            block.run();
        }
        return this;
    }

    /**
     * Ensures the property exists in the configuration (is not null).
     * If it is a String, an empty string ("") IS allowed.
     */
    public ValidatorUtils required(final String property, final Object value)
    {
        if (value == null)
        {
            this.result.addError(property, "'" + property + "' is required");
        }
        return this;
    }

    /**
     * Ensures the property exists AND contains actual text.
     * Fails on null, "", and "   ".
     */
    public ValidatorUtils notBlank(final String property, final String value)
    {
        // We can safely reuse .required() for the null check
        this.required(property, value);

        if (value != null && value.isBlank())
        {
            this.result.addError(property, "'" + property + "' cannot be blank");
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

    public ValidatorUtils requirePositive(String property, Integer value)
    {
        if (value == null || value <= 0L)
        {
            result.addError(property, "'" + property + "' must be a positive integer value, got: " + value);
        }
        return this;
    }

    public ValidatorUtils requirePositive(String property, Long value)
    {
        if (value == null || value <= 0L)
        {
            result.addError(property, "'" + property + "' must be a positive integer value, got: " + value);
        }
        return this;
    }

    public ValidatorUtils requirePositive(String property, Duration value)
    {
        if (value == null || value.compareTo(Duration.ZERO) < 1)
        {
            return requirePositive(property, value, Duration.ofDays(1));
        }
        return this;
    }

    public ValidatorUtils requirePositive(String property, Duration value, Duration max)
    {
        if (value == null || value.compareTo(Duration.ZERO) <= 0)
        {
            result.addError(property, "'" + property + "' must be a positive duration, got: " + value);
        }
        else if (value.compareTo(max) > 0)
        {
            result.addError(property, "'" + property + "' exceeds maximum allowed duration of " + max + ", got: " + value);
        }
        return this;
    }

    public ValidatorUtils requirePositive(String property, DataSize value)
    {
        if (value == null || value.bytes() <= 0)
        {
            result.addError(property, "'" + property + "' must be a positive data size, got: " + value);
        }
        return this;
    }

    public ValidatorUtils requiredRegexp(String property, String expression)
    {
        notBlank(property, expression);

        if (expression != null && !expression.isBlank())
        {
            try
            {
                Pattern.compile(expression);
            }
            catch (final PatternSyntaxException e)
            {
                invalid(property, expression, "Invalid regex format: " + e.getDescription());
            }
        }
        return this;
    }

    public ValidatorUtils validRegexReplacement(final String property, final String regexp, final String replacement)
    {
        // Skip if base values are missing or invalid; required() and requiredRegexp() will catch those.
        if (regexp == null || regexp.isBlank() || replacement == null)
        {
            return this;
        }

        try
        {
            final int groupCount = Pattern.compile(regexp).matcher("").groupCount();
            final Pattern dummyPattern = Pattern.compile("^" + "()".repeat(groupCount));
            dummyPattern.matcher("").replaceAll(replacement);
        }
        catch (final java.util.regex.PatternSyntaxException ignored)
        {
            // The regex itself is broken. Let requiredRegexp() report this error.
        }
        catch (final IndexOutOfBoundsException | IllegalArgumentException e)
        {
            this.result.addError(property, "Invalid replacement pattern or capture group reference: " + e.getMessage());
        }

        return this;
    }
}
