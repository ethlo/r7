package com.ethlo.r7.util;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.ethlo.r7.api.TextValues;
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

    /**
     * Rejects a value that a filter would later refuse to store as a header, cookie, or query
     * parameter: any character above ISO-8859-1, DEL, or a C0 control character other than tab
     * (this includes CR and LF, which would otherwise allow header/response splitting).
     * <p>
     * Filters set values like this on every matching request via {@code TextValues.requireStorable},
     * which throws rather than returning a boolean. Catching an unstorable value here, at config
     * validation time, turns a per-request 500 into a startup error that names the offending field.
     * A {@code null} value is not reported here; use {@link #required(String, Object)} for that.
     */
    public ValidatorUtils safeHeaderText(final String property, final String value)
    {
        if (value == null)
        {
            return this;
        }
        for (int i = 0, len = value.length(); i < len; i++)
        {
            final char character = value.charAt(i);
            if (character > TextValues.MAX_STORABLE || character == 0x7F || (character < 0x20 && character != '\t'))
            {
                invalid(property, value, String.format(
                        "character U+%04X at index %d cannot appear in an HTTP header, cookie, or query "
                                + "parameter value; use printable ISO-8859-1 text", (int) character, i));
                return this;
            }
        }
        return this;
    }

    /**
     * Characters permitted in an HTTP token (RFC 9110 §5.6.2) beyond letters and digits.
     */
    private static final String TOKEN_SPECIALS = "!#$%&'*+-.^_`|~";

    /**
     * Rejects a header or cookie name that is not a valid HTTP token: letters, digits, and
     * {@code !#$%&'*+-.^_`|~} only. Unlike {@link #safeHeaderText}, this also rejects spaces,
     * colons, and other structurally invalid characters that {@code HttpString.tryFromString}
     * would otherwise accept, producing a malformed header line at request time.
     */
    public ValidatorUtils httpToken(final String property, final String value)
    {
        if (value == null)
        {
            return this;
        }
        if (value.isEmpty())
        {
            invalid(property, value, "must not be empty; an HTTP token requires at least one character");
            return this;
        }
        for (int i = 0, len = value.length(); i < len; i++)
        {
            final char character = value.charAt(i);
            final boolean isTokenChar = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || TOKEN_SPECIALS.indexOf(character) >= 0;
            if (!isTokenChar)
            {
                invalid(property, value, String.format(
                        "character '%s' at index %d is not a valid HTTP token character; "
                                + "names may only contain letters, digits, and !#$%%&'*+-.^_`|~",
                        character, i));
                return this;
            }
        }
        return this;
    }

    /**
     * Rejects a value containing the given character, e.g. a {@code ;} in a cookie attribute,
     * which would otherwise let a config value inject additional {@code Set-Cookie} attributes
     * when the header is built by string concatenation.
     */
    public ValidatorUtils excludesChar(final String property, final String value, final char forbidden, final String reason)
    {
        if (value != null && value.indexOf(forbidden) >= 0)
        {
            invalid(property, value, reason);
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
