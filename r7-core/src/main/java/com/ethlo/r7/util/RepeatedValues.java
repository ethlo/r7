package com.ethlo.r7.util;

import java.util.regex.Pattern;

import com.ethlo.r7.api.Cookie;
import com.ethlo.r7.api.Cookies;

/**
 * Value checks over a name that may occur more than once - a repeated query parameter, a header
 * sent on several lines, the same cookie twice - pass only if every occurrence passes.
 * <p>
 * Checking just the first would let {@code ?role=user&role=admin} satisfy a check on
 * {@code role} while the upstream reads another occurrence: frameworks disagree on which one
 * wins (Spring the first, PHP the last, Express all of them as an array, ASP.NET all of them
 * joined with commas). Requiring all of them to pass holds whichever convention the upstream
 * follows. At least one occurrence must be present.
 */
public final class RepeatedValues
{
    private RepeatedValues()
    {
    }

    /**
     * @return true if there is at least one value and every value matches the whole pattern
     */
    public static boolean allMatch(final Iterable<String> values, final Pattern pattern)
    {
        boolean any = false;
        for (final String value : values)
        {
            if (value == null || !RegexBudget.matcher(pattern, value).matches())
            {
                return false;
            }
            any = true;
        }
        return any;
    }

    /**
     * @return true if there is at least one value and every value equals {@code expected}
     */
    public static boolean allEqual(final Iterable<String> values, final String expected)
    {
        boolean any = false;
        for (final String value : values)
        {
            if (!expected.equals(value))
            {
                return false;
            }
            any = true;
        }
        return any;
    }

    /**
     * As {@link #allMatch(Iterable, Pattern)}, over every cookie with this name.
     */
    public static boolean allCookiesMatch(final Cookies cookies, final String name, final Pattern pattern)
    {
        boolean any = false;
        for (final Cookie cookie : cookies.all())
        {
            if (name.equals(cookie.name()))
            {
                if (cookie.value() == null || !RegexBudget.matcher(pattern, cookie.value()).matches())
                {
                    return false;
                }
                any = true;
            }
        }
        return any;
    }

    /**
     * As {@link #allEqual(Iterable, String)}, over every cookie with this name.
     */
    public static boolean allCookiesEqual(final Cookies cookies, final String name, final String expected)
    {
        boolean any = false;
        for (final Cookie cookie : cookies.all())
        {
            if (name.equals(cookie.name()))
            {
                if (!expected.equals(cookie.value()))
                {
                    return false;
                }
                any = true;
            }
        }
        return any;
    }
}
