package com.ethlo.r7.util;

import java.util.regex.Pattern;

import com.ethlo.r7.api.Cookies;
import com.ethlo.r7.api.MultiAttributes;
import com.ethlo.r7.api.QueryParams;

/**
 * Value checks over a name that may occur more than once - a repeated query parameter, a header
 * sent on several lines, the same cookie twice - pass only if every occurrence passes.
 * <p>
 * Checking just one occurrence would let {@code ?role=user&role=admin} satisfy a check on
 * {@code role} while the upstream reads another: frameworks disagree on which one wins (Spring
 * the first, PHP the last, Express all of them as an array, ASP.NET all of them joined with
 * commas). Requiring all of them to pass holds whichever convention the upstream follows. At
 * least one occurrence must be present.
 * <p>
 * Every occurrence in one check draws on a single {@link RegexBudget}, so repeating a value
 * cannot multiply the work, and the common single-occurrence case allocates no more than a
 * single-value check did.
 */
public final class RepeatedValues
{
    private static final String COOKIE = "Cookie";

    private RepeatedValues()
    {
    }

    public static boolean allMatch(final QueryParams params, final String name, final Pattern pattern)
    {
        final int count = params.count(name);
        if (count == 0)
        {
            return false;
        }
        if (count == 1)
        {
            final String value = params.getFirst(name);
            return value != null && RegexBudget.matcher(pattern, value).matches();
        }
        return allMatch(params.getAll(name), pattern);
    }

    public static boolean allEqual(final QueryParams params, final String name, final String expected)
    {
        final int count = params.count(name);
        if (count == 0)
        {
            return false;
        }
        if (count == 1)
        {
            return expected.equals(params.getFirst(name));
        }
        return allEqual(params.getAll(name), expected);
    }

    /**
     * Header containers traverse their values through a reusable view, without allocating.
     */
    public static boolean allMatch(final MultiAttributes headers, final String name, final Pattern pattern)
    {
        return allMatch(headers.getAll(name), pattern);
    }

    public static boolean allEqual(final MultiAttributes headers, final String name, final String expected)
    {
        return allEqual(headers.getAll(name), expected);
    }

    /**
     * @return true if there is at least one value and every value matches the whole pattern
     */
    public static boolean allMatch(final Iterable<String> values, final Pattern pattern)
    {
        RegexBudget.SharedBudget budget = null;
        boolean any = false;
        for (final String value : values)
        {
            if (value == null)
            {
                return false;
            }
            if (budget == null)
            {
                budget = new RegexBudget.SharedBudget(pattern);
            }
            if (!budget.matches(value))
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
     * As {@link #allMatch(Iterable, Pattern)}, over every cookie with this name in the request's
     * {@code Cookie} header lines.
     * <p>
     * Read from the raw header rather than a parsed cookie map: a map keeps one of several cookies
     * with the same name, and parsers disagree on which - some the last, many upstream ones the
     * first - so {@code role=admin; role=user} would pass a check on {@code role} that the
     * upstream reads as {@code admin}.
     * <p>
     * The parsed cookies are consulted first, so that a name the request does not carry is
     * refused without scanning. How many cookies a request may carry is bounded by
     * {@code limits.max_header_size}.
     */
    public static boolean allCookiesMatch(final Cookies cookies, final MultiAttributes headers, final String name, final Pattern pattern)
    {
        return cookies.contains(name) && cookieCheck(headers, name, pattern, null);
    }

    /**
     * As {@link #allEqual(Iterable, String)}, over every cookie with this name; see
     * {@link #allCookiesMatch(Cookies, MultiAttributes, String, Pattern)}.
     */
    public static boolean allCookiesEqual(final Cookies cookies, final MultiAttributes headers, final String name, final String expected)
    {
        return cookies.contains(name) && cookieCheck(headers, name, null, expected);
    }

    private static boolean cookieCheck(final MultiAttributes headers, final String name, final Pattern pattern, final String expected)
    {
        RegexBudget.SharedBudget budget = null;
        boolean any = false;
        for (final String line : headers.getAll(COOKIE))
        {
            int pos = 0;
            final int len = line.length();
            while (pos < len)
            {
                int end = line.indexOf(';', pos);
                if (end < 0)
                {
                    end = len;
                }
                int start = pos;
                while (start < end && isSpace(line.charAt(start)))
                {
                    start++;
                }
                // Bounded to this segment: an unbounded indexOf would rescan the rest of the line
                // for every '='-less segment, quadratic in the header's length.
                int eq = -1;
                for (int i = start; i < end; i++)
                {
                    if (line.charAt(i) == '=')
                    {
                        eq = i;
                        break;
                    }
                }
                // Whitespace around the name is not RFC 6265, but a lenient upstream parser trims
                // it: "role =admin" has to count as a role cookie here too.
                int nameEnd = eq;
                while (nameEnd > start && isSpace(line.charAt(nameEnd - 1)))
                {
                    nameEnd--;
                }
                if (eq >= 0 && nameEnd - start == name.length() && line.regionMatches(start, name, 0, name.length()))
                {
                    final String value = cookieValue(line, eq + 1, end);
                    if (expected != null)
                    {
                        if (!expected.equals(value))
                        {
                            return false;
                        }
                    }
                    else
                    {
                        if (budget == null)
                        {
                            budget = new RegexBudget.SharedBudget(pattern);
                        }
                        if (!budget.matches(value))
                        {
                            return false;
                        }
                    }
                    any = true;
                }
                pos = end + 1;
            }
        }
        return any;
    }

    /**
     * The value between {@code from} and {@code to}, without surrounding spaces or the optional
     * double quotes of RFC 6265 §4.1.1, as a parsed cookie's value is presented.
     */
    private static String cookieValue(final String line, final int from, final int to)
    {
        int start = from;
        int end = to;
        while (start < end && isSpace(line.charAt(start)))
        {
            start++;
        }
        while (end > start && isSpace(line.charAt(end - 1)))
        {
            end--;
        }
        if (end - start >= 2 && line.charAt(start) == '"' && line.charAt(end - 1) == '"')
        {
            start++;
            end--;
        }
        return line.substring(start, end);
    }

    private static boolean isSpace(final char c)
    {
        return c == ' ' || c == '\t';
    }
}
