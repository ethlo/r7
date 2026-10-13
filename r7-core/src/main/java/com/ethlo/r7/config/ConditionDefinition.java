package com.ethlo.r7.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ethlo.r7.condition.AndCondition;
import com.ethlo.r7.condition.Condition;
import com.ethlo.r7.condition.ConditionScope;
import com.ethlo.r7.condition.NotCondition;
import com.ethlo.r7.condition.OrCondition;
import com.ethlo.r7.util.PredicateRegistry;
import com.ethlo.r7.validation.ValidationResult;
import com.fasterxml.jackson.annotation.JsonCreator;

public final class ConditionDefinition
{
    private final Map<String, Object> predicates = new HashMap<>();
    public List<ConditionDefinition> and;
    public List<ConditionDefinition> or;
    public ConditionDefinition not;

    // Only 'match: []' is fallback-only. 'match: { and: [] }' leaves the same fields as '[]',
    // so how the node was written has to be kept to refuse the explicit operator.
    private boolean writtenAsList;

    // Operator shape errors found while binding. They are reported by validateTree rather than
    // thrown from the creator, where Jackson's unwrapped message would lose the route and path.
    private final Map<String, String> shapeErrors = new HashMap<>();

    public ConditionDefinition()
    {
    }

    @JsonCreator
    public static ConditionDefinition create(Object raw)
    {
        ConditionDefinition def = new ConditionDefinition();

        if (raw instanceof List<?> list)
        {
            // Case: match: [ {PathStartsWith: /hello}, {Method: GET} ]
            def.and = list.stream().map(ConditionDefinition::create).toList();
            def.writtenAsList = true;
        }
        else if (raw instanceof Map<?, ?> map)
        {
            // Case: match: { PathStartsWith: /hello, or: [...] }
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                switch (key)
                {
                    case "and" -> def.and = def.createList("and", v);
                    case "or" -> def.or = def.createList("or", v);
                    case "not" -> def.not = def.createNot(v);

                    // Jackson natively handles whether 'v' is a String or an Object
                    default -> def.predicates.put(key, v);
                }
            });
        }
        else if (raw instanceof String s)
        {
            // Spring Cloud Gateway's 'Name=value' shorthand is not supported here: as a bare
            // YAML scalar it carries no predicate name Jackson can bind to, so it used to be
            // silently dropped into an empty (always-true) node instead of matching anything.
            throw new ConfigurationException(
                    "Invalid match entry '" + s + "': the 'Name=value' shorthand is not supported. " +
                            "Use the map form instead, e.g. '- Path: {path: /admin}' or '- PathPrefix: {prefix: /admin/}'.");
        }
        return def;
    }

    private ConditionDefinition createNot(final Object v)
    {
        // A list here was ANDed: 'not: [A, B]' meant NOT(A AND B), which reads just as well as
        // "neither A nor B", NOT(A OR B). Making the operator explicit leaves one reading.
        if (v instanceof List<?>)
        {
            shapeErrors.put("not", "'not' takes one condition, not a list: write 'not: { Method: ... }', "
                    + "or 'not: { or: [...] }' for none of several, 'not: { and: [...] }' for not all of them");
            return null;
        }
        return create(v);
    }

    private List<ConditionDefinition> createList(final String operator, final Object v)
    {
        if (v instanceof List<?> list)
        {
            return list.stream().map(ConditionDefinition::create).toList();
        }
        // A mapping here used to become an empty list: 'and: { Path: ... }' loaded and the
        // route never matched, and under an 'or' it surfaced as a misleading "Empty 'or'".
        shapeErrors.put(operator, "'" + operator + "' takes a list of conditions, e.g. '" + operator + ": [ {Method: ...}, {Path: ...} ]'");
        return null;
    }

    /**
     * Builds the condition tree. Errors are reported on {@code result} under the node's path, as
     * {@link #validateTree} reports them ({@code match[0].Host.leg}), and a refused entry builds
     * to {@link Condition#FALSE} so the tree stays whole but fails closed.
     */
    public Condition build(final PredicateRegistry registry, final ConditionScope scope, final ValidationResult result)
    {
        return build(registry, scope, result, "match");
    }

    private Condition build(final PredicateRegistry registry, final ConditionScope scope, final ValidationResult result, final String path)
    {
        if (!shapeErrors.isEmpty())
        {
            // validateTree has reported it; never let a half-bound node build to true.
            return Condition.FALSE;
        }

        final List<Condition> list = new ArrayList<>();

        final ValidationResult nodeResult = result.nested(path);
        this.predicates.forEach((name, value) -> list.add(registry.condition(name, value, scope, nodeResult)));

        if (this.and != null && !this.and.isEmpty())
        {
            final List<Condition> andChildren = new ArrayList<>(this.and.size());
            for (int i = 0; i < this.and.size(); i++)
            {
                andChildren.add(this.and.get(i).build(registry, scope, result, (writtenAsList ? path : path + ".and") + "[" + i + "]"));
            }

            if (andChildren.size() == 1)
            {
                list.add(andChildren.getFirst());
            }
            else
            {
                list.add(new AndCondition(andChildren));
            }
        }

        if (this.or != null && !this.or.isEmpty())
        {
            final List<Condition> orChildren = new ArrayList<>(this.or.size());
            for (int i = 0; i < this.or.size(); i++)
            {
                orChildren.add(this.or.get(i).build(registry, scope, result, path + ".or[" + i + "]"));
            }
            list.add(new OrCondition(orChildren));
        }

        if (this.not != null)
        {
            list.add(new NotCondition(this.not.build(registry, scope, result, path + ".not")));
        }

        if (list.isEmpty())
        {
            return Condition.TRUE;
        }
        if (list.size() == 1)
        {
            return list.getFirst();
        }

        return new AndCondition(list);
    }

    /**
     * Recursively validates the tree's shape; {@link #build} checks the predicates it names. Errors
     * carry the node's path below {@code result}, e.g. {@code routes.orders.match.or[1].not}.
     */
    public void validateTree(final ValidationResult result)
    {
        validateTree(result, "match", true);
    }

    /**
     * True for {@code match: []} written as a list, the documented fallback-only route: it
     * matches nothing and is reachable only as a fallback. Every other empty form
     * ({@code {}}, {@code { and: [] }}, {@code { or: [] }}) is refused by {@link #validateTree},
     * since {@link #build} would turn it into an always-true predicate.
     */
    public boolean isFallbackOnly()
    {
        return writtenAsList && and.isEmpty();
    }

    private boolean isEmpty()
    {
        return predicates.isEmpty()
                && (and == null || and.isEmpty())
                && (or == null || or.isEmpty())
                && not == null;
    }

    private void validateTree(final ValidationResult result, final String path, final boolean topLevel)
    {
        if (!shapeErrors.isEmpty())
        {
            shapeErrors.forEach((operator, message) -> result.addError(path + "." + operator, message));
            return;
        }

        // An empty node used to build to true: 'not: {}' or '- {}' made the route a catch-all,
        // and 'or: []' silently dropped out of the tree. Only a top-level '[]' is allowed, and
        // the caller handles it before validation.
        if (and != null && and.isEmpty())
        {
            result.addError(writtenAsList ? path : path + ".and", writtenAsList
                    ? "Empty list: name at least one predicate, or remove it"
                    : "Empty 'and': name at least one predicate, or remove it");
        }
        if (or != null && or.isEmpty())
        {
            result.addError(path + ".or", "Empty 'or': name at least one predicate, or remove it");
        }
        if (isEmpty())
        {
            if (and == null && or == null)
            {
                result.addError(path, topLevel
                        ? "Empty match: name at least one predicate, or write 'match: []' for a fallback-only route"
                        : "Empty condition: name at least one predicate, or remove it");
            }
            return;
        }

        if (and != null)
        {
            for (int i = 0; i < and.size(); i++)
            {
                and.get(i).validateTree(result, (writtenAsList ? path : path + ".and") + "[" + i + "]", false);
            }
        }

        if (or != null)
        {
            for (int i = 0; i < or.size(); i++)
            {
                or.get(i).validateTree(result, path + ".or[" + i + "]", false);
            }
        }

        if (not != null)
        {
            not.validateTree(result, path + ".not", false);
        }
    }

    @Override
    public String toString()
    {
        final List<String> parts = new ArrayList<>();

        for (final Map.Entry<String, Object> entry : this.predicates.entrySet())
        {
            parts.add(entry.getKey() + "=" + entry.getValue());
        }

        if (this.not != null)
        {
            parts.add("NOT(" + this.not + ")");
        }

        if (this.and != null && !this.and.isEmpty())
        {
            if (this.and.size() == 1)
            {
                parts.add(this.and.getFirst().toString());
            }
            else
            {
                final List<String> andStrings = this.and.stream().map(ConditionDefinition::toString).toList();
                parts.add("AND(" + String.join(", ", andStrings) + ")");
            }
        }

        if (this.or != null && !this.or.isEmpty())
        {
            if (this.or.size() == 1)
            {
                parts.add(this.or.getFirst().toString());
            }
            else
            {
                final List<String> orStrings = this.or.stream().map(ConditionDefinition::toString).toList();
                parts.add("OR(" + String.join(", ", orStrings) + ")");
            }
        }

        if (parts.isEmpty())
        {
            return "MATCH_NONE";
        }

        if (parts.size() == 1)
        {
            return parts.getFirst();
        }

        return "AND(" + String.join(", ", parts) + ")";
    }
}