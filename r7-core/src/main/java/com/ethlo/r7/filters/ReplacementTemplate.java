package com.ethlo.r7.filters;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.ethlo.r7.util.PathEncoder;

/**
 * The replacement syntax of {@code RewritePath} and {@code TemplateRedirect}: {@code $1} for a
 * numbered group, {@code {name}} for a named one, {@code {query.NAME}} for a query parameter of
 * the client's request and {@code \x} for the literal character x.
 * <p>
 * A brace is syntax only when unescaped, and an unescaped one that is not a placeholder naming
 * something that exists is refused at load. {@code ${...}} is environment interpolation and
 * never reaches here as anything but a mistake.
 */
final class ReplacementTemplate
{
    /**
     * The pattern of a filter that has no source: a template with no captures.
     */
    static final Pattern NO_GROUPS = Pattern.compile("");

    private static final String QUERY_PREFIX = "{query.";

    private ReplacementTemplate()
    {
    }

    /**
     * The URI component a piece of request text lands in, which decides how it is encoded.
     */
    enum Component
    {
        PATH, QUERY_OR_FRAGMENT;

        String encode(final String decoded)
        {
            return this == PATH ? PathEncoder.encode(decoded) : PathEncoder.encodeQueryValue(decoded);
        }
    }

    /**
     * One piece of a parsed template: literal text, a capture group, or a query parameter
     * ({@code query} set), the last two with the component their position in the template puts
     * them in.
     */
    record Part(String literal, int group, String query, Component component)
    {
        static Part literal(final String text, final Component component)
        {
            return new Part(text, -1, null, component);
        }

        boolean isLiteral()
        {
            return this.literal != null;
        }
    }

    /**
     * Parses a template so each capture group can be encoded for where it lands, which
     * {@link java.util.regex.Matcher#replaceFirst} cannot do. A group is in the query or fragment
     * once a literal {@code ?} or {@code #} precedes it; request text can never move that
     * boundary, because it is encoded before it is spliced in.
     *
     * @param wire whether the result is sent as written, so a literal brace must be written
     *             percent-encoded: r7 never emits a raw brace in a URI. A decoded result
     *             ({@code RewritePath}'s path) is encoded later, where the brace is encoded with it.
     * @throws IllegalArgumentException with a message that names the mistake
     */
    static List<Part> parse(final String template, final Pattern pattern, final boolean wire)
    {
        final int groupCount = pattern.matcher("").groupCount();
        final List<Part> parts = new ArrayList<>();
        final StringBuilder literal = new StringBuilder();
        Component component = Component.PATH;
        int i = 0;
        while (i < template.length())
        {
            final char c = template.charAt(i);
            if (c == '\\' && i + 1 < template.length())
            {
                appendLiteral(literal, template.charAt(i + 1), wire);
                component = componentAfter(template.charAt(i + 1), component);
                i += 2;
            }
            else if (c == '$')
            {
                if (i + 1 < template.length() && template.charAt(i + 1) == '{')
                {
                    throw new IllegalArgumentException("Write a named group as {name}, not $${name}.");
                }
                // Same rule as Matcher.appendReplacement: take further digits only while the
                // number they make is still a group of the pattern.
                int ref = i + 1 < template.length() ? template.charAt(i + 1) - '0' : -1;
                if (ref < 0 || ref > 9)
                {
                    throw new IllegalArgumentException("Illegal group reference in: " + template + " (write \\$ for a literal $)");
                }
                if (pattern == NO_GROUPS || ref > groupCount)
                {
                    throw new IllegalArgumentException("$" + ref + " needs a source pattern with that many groups.");
                }
                i += 2;
                while (i < template.length() && Character.isDigit(template.charAt(i)))
                {
                    final int next = ref * 10 + (template.charAt(i) - '0');
                    if (next > groupCount)
                    {
                        break;
                    }
                    ref = next;
                    i++;
                }
                flush(parts, literal, component);
                parts.add(new Part(null, ref, null, component));
            }
            else if (c == '{')
            {
                final int close = template.indexOf('}', i);
                if (close < 0)
                {
                    throw new IllegalArgumentException("Unterminated { in: " + template + " (write \\{ for a literal brace)");
                }
                final String name = template.substring(i + 1, close);
                flush(parts, literal, component);
                if (template.startsWith(QUERY_PREFIX, i) && close > i + QUERY_PREFIX.length())
                {
                    parts.add(new Part(null, -1, name.substring("query.".length()), component));
                }
                else
                {
                    final Integer group = pattern.namedGroups().get(name);
                    if (group == null)
                    {
                        throw new IllegalArgumentException("{" + name + "} needs a source pattern with that named group (write \\{ for a literal brace).");
                    }
                    parts.add(new Part(null, group, null, component));
                }
                i = close + 1;
            }
            else
            {
                appendLiteral(literal, c, wire);
                component = componentAfter(c, component);
                i++;
            }
        }
        // A trailing part carries the component the template ends in, which is where the
        // unmatched remainder of the path is appended.
        parts.add(Part.literal(literal.toString(), component));
        return List.copyOf(parts);
    }

    private static void flush(final List<Part> parts, final StringBuilder literal, final Component component)
    {
        if (!literal.isEmpty())
        {
            parts.add(Part.literal(literal.toString(), component));
            literal.setLength(0);
        }
    }

    private static void appendLiteral(final StringBuilder literal, final char c, final boolean wire)
    {
        if (wire && c == '{')
        {
            literal.append("%7B");
        }
        else if (wire && c == '}')
        {
            literal.append("%7D");
        }
        else
        {
            literal.append(c);
        }
    }

    private static Component componentAfter(final char c, final Component current)
    {
        return c == '?' || c == '#' ? Component.QUERY_OR_FRAGMENT : current;
    }
}
