package com.ethlo.venturi.core.predicates;

import java.util.List;

import com.ethlo.venturi.api.GatewayPredicate;

public final class VenturiPredicates
{

    /**
     * Case-insensitive header matcher using zero-allocation CharSequence comparison.
     */
    public static GatewayPredicate headerMatches(final CharSequence name, final CharSequence value)
    {
        return request -> { // Changed from exchange -> request
            final boolean[] found = {false};
            request.headers().forEach((k, v) -> {
                if (!found[0] && contentEqualsIgnoreCase(k, name) && contentEqualsIgnoreCase(v, value))
                {
                    found[0] = true;
                }
            });
            return found[0];
        };
    }

    public static GatewayPredicate and(final List<GatewayPredicate> predicates)
    {
        return request -> {
            for (final GatewayPredicate p : predicates)
            {
                if (!p.test(request)) return false;
            }
            return true;
        };
    }

    public static GatewayPredicate or(final List<GatewayPredicate> predicates)
    {
        return request -> {
            for (final GatewayPredicate p : predicates)
            {
                if (p.test(request)) return true;
            }
            return false;
        };
    }

    public static GatewayPredicate method(final List<CharSequence> expected)
    {
        return request -> expected
                .stream()
                .anyMatch(e -> contentEqualsIgnoreCase(e, request.method()));
    }

    public static GatewayPredicate pathStartsWith(final CharSequence prefix)
    {
        final int prefixLen = prefix.length();
        return request -> {
            final CharSequence path = request.path();
            if (path.length() < prefixLen)
            {
                return false;
            }

            for (int i = 0; i < prefixLen; i++)
            {
                if (path.charAt(i) != prefix.charAt(i))
                {
                    return false;
                }
            }
            return true;
        };
    }

    private static boolean contentEqualsIgnoreCase(final CharSequence cs1, final CharSequence s2)
    {
        if (cs1 == null || s2 == null)
        {
            return false;
        }

        final int len = cs1.length();

        if (len != s2.length())
        {
            return false;
        }

        for (int i = 0; i < len; i++)
        {
            if (Character.toLowerCase(cs1.charAt(i)) != Character.toLowerCase(s2.charAt(i)))
            {
                return false;
            }
        }
        return true;
    }
}