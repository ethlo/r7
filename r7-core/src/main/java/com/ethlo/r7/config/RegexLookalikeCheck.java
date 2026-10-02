package com.ethlo.r7.config;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ethlo.r7.doc.ExactMatch;
import com.ethlo.r7.util.PredicateRegistry;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;

/**
 * Warns when a value given to an exact-match predicate ({@code Path}, {@code RequestHeader},
 * ...) looks like a regular expression. Such a config is valid and loads, but {@code Path: /api/.*}
 * matches only a client that sends a literal {@code .*}, so the route silently never matches.
 * Spring Cloud Gateway users meet this first: its {@code Path} takes {@code /api/**} and its
 * {@code Header} and {@code Cookie} take a regex, where r7 keeps exact and regex matching apart.
 * <p>
 * The fields checked are the record components marked {@link ExactMatch}. Only constructs that
 * a literal value practically never contains count, so a warning is worth reading: a lone
 * {@code .}, {@code +} or {@code ?} does not.
 */
public final class RegexLookalikeCheck
{
    private static final Pattern[] REGEX_CONSTRUCTS = {
            // Anchors at either end
            Pattern.compile("^\\^"),
            Pattern.compile("(?<!\\\\)\\$$"),
            // Any-character repetition
            Pattern.compile("(?<!\\\\)\\.[*+]"),
            // Character classes and escapes: [a-z], [^/], [\d], \d, \w, \s, \.
            Pattern.compile("\\[\\^?[^\\]]*(?:[A-Za-z0-9]-[A-Za-z0-9]|\\\\[dDwWsS])[^\\]]*\\]"),
            Pattern.compile("\\[\\^[^\\]]+\\]"),
            Pattern.compile("\\\\[dDwWsSbB.]"),
            // Alternation in a group, inline flags, counted repetition
            Pattern.compile("\\((?:\\?:)?[^()|]*\\|[^()]*\\)"),
            Pattern.compile("\\(\\?[a-zA-Z]+\\)"),
            Pattern.compile("[^\\s{]\\{\\d+(?:,\\d*)?}")
    };

    private static final Pattern WILDCARD = Pattern.compile("\\*+");
    private static final Pattern TEMPLATE = Pattern.compile("\\{[A-Za-z_][A-Za-z0-9_]*(?::[^}]*)?}");

    private final PredicateRegistry predicateRegistry;

    public RegexLookalikeCheck(final PredicateRegistry predicateRegistry)
    {
        this.predicateRegistry = predicateRegistry;
    }

    /**
     * @param root      the routes file's tree, after interpolation
     * @param positions where each value in {@code root} was written
     * @return one readable warning per suspicious value, naming its path and position
     */
    public List<String> check(final JsonNode root, final YamlSourcePositions positions)
    {
        final List<String> warnings = new ArrayList<>();
        final JsonNode routes = root.path("routes");
        for (int i = 0; i < routes.size(); i++)
        {
            final Location route = Location.ROOT.property("routes").index(i);
            checkCondition(routes.get(i).path("match"), route.property("match"), positions, warnings);
        }
        return warnings;
    }

    private void checkCondition(final JsonNode node, final Location location, final YamlSourcePositions positions, final List<String> warnings)
    {
        if (node.isArray())
        {
            for (int i = 0; i < node.size(); i++)
            {
                checkCondition(node.get(i), location.index(i), positions, warnings);
            }
        }
        else if (node.isObject())
        {
            for (final Map.Entry<String, JsonNode> entry : node.properties())
            {
                final String key = entry.getKey();
                if (key.equals("and") || key.equals("or") || key.equals("not"))
                {
                    checkCondition(entry.getValue(), location.property(key), positions, warnings);
                }
                else
                {
                    checkPredicate(key, entry.getValue(), location.property(key), positions, warnings);
                }
            }
        }
    }

    private void checkPredicate(final String name, final JsonNode args, final Location location, final YamlSourcePositions positions, final List<String> warnings)
    {
        final Class<?> configClass = this.predicateRegistry.configClass(name).orElse(null);
        if (configClass == null || !configClass.isRecord())
        {
            // An unknown predicate is an error reported elsewhere
            return;
        }

        for (final RecordComponent component : configClass.getRecordComponents())
        {
            final ExactMatch exactMatch = component.getAnnotation(ExactMatch.class);
            if (exactMatch != null)
            {
                // The same naming the mapper binds with: no mapper or field context needed for it
                final String field = PropertyNamingStrategies.SNAKE_CASE.nameForField(null, null, component.getName());
                checkValue(name, exactMatch, args.path(field), location.property(field), positions, warnings);
            }
        }
    }

    private void checkValue(final String predicate, final ExactMatch exactMatch, final JsonNode value, final Location location, final YamlSourcePositions positions, final List<String> warnings)
    {
        if (value.isArray())
        {
            for (int i = 0; i < value.size(); i++)
            {
                checkValue(predicate, exactMatch, value.get(i), location.index(i), positions, warnings);
            }
            return;
        }
        if (!value.isString())
        {
            return;
        }

        final String suspicion = suspicion(value.asString(), exactMatch.wildcards());
        if (suspicion != null)
        {
            final StringBuilder sb = new StringBuilder("[").append(location.path()).append("]");
            positions.of(location.pointer()).ifPresent(p -> sb.append(" line ").append(p.line()).append(", column ").append(p.column()));
            sb.append(": ").append(predicate).append(" compares this value exactly, but it looks like ")
                    .append(suspicion).append(". ").append(exactMatch.alternative());
            warnings.add(sb.toString());
        }
    }

    /**
     * @return what the value looks like, for the warning, or {@code null} when it looks literal.
     * Only the matched construct is quoted, never the whole value: a cookie or header value can
     * be a secret.
     */
    static String suspicion(final String value, final boolean wildcards)
    {
        for (final Pattern construct : REGEX_CONSTRUCTS)
        {
            final Matcher matcher = construct.matcher(value);
            if (matcher.find())
            {
                return "a regular expression ('" + matcher.group() + "')";
            }
        }

        if (wildcards)
        {
            final Matcher wildcard = WILDCARD.matcher(value);
            if (wildcard.find())
            {
                return "a wildcard pattern ('" + wildcard.group() + "')";
            }
            final Matcher template = TEMPLATE.matcher(value);
            if (template.find())
            {
                return "a template ('" + template.group() + "')";
            }
        }
        return null;
    }

    /**
     * A node's address both ways: the pointer {@link YamlSourcePositions} is keyed by, and the
     * path an operator reads, written as the config errors write it ({@code routes[0].match}).
     */
    private record Location(JsonPointer pointer, String path)
    {
        static final Location ROOT = new Location(JsonPointer.empty(), "");

        Location property(final String name)
        {
            return new Location(this.pointer.appendProperty(name), this.path.isEmpty() ? name : this.path + "." + name);
        }

        Location index(final int index)
        {
            return new Location(this.pointer.appendIndex(index), this.path + "[" + index + "]");
        }
    }
}
