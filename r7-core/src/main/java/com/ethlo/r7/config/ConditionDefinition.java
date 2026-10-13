package com.ethlo.r7.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.predicates.AndPredicate;
import com.ethlo.r7.predicates.NotPredicate;
import com.ethlo.r7.predicates.OrPredicate;
import com.ethlo.r7.util.PredicateRegistry;
import com.ethlo.r7.validation.ValidationResult;
import com.fasterxml.jackson.annotation.JsonCreator;

public final class ConditionDefinition
{
    private final Map<String, Object> predicates = new HashMap<>();
    public List<ConditionDefinition> and;
    public List<ConditionDefinition> or;
    public ConditionDefinition not;

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
        }
        else if (raw instanceof Map<?, ?> map)
        {
            // Case: match: { PathStartsWith: /hello, or: [...] }
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                switch (key)
                {
                    case "and" -> def.and = createList(v);
                    case "or" -> def.or = createList(v);
                    case "not" -> def.not = create(v);

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

    private static List<ConditionDefinition> createList(Object v)
    {
        if (v instanceof List<?> list)
        {
            return list.stream().map(ConditionDefinition::create).toList();
        }
        return Collections.emptyList();
    }

    public GatewayPredicate build(final PredicateRegistry registry, final ValidationResult validationResult)
    {
        final List<GatewayPredicate> list = new ArrayList<>();

        this.predicates.forEach((name, value) ->
        {
            try
            {
                list.add(registry.create(name, value));
            }
            catch (final ConfigurationException | IllegalArgumentException e)
            {
                // 1. Report the error so the user sees it
                validationResult.addError(name, e.getMessage());
                // 2. Insert the Poison Pill so the tree remains structurally intact but fails-closed
                list.add(FalsePredicate.INSTANCE);
            }
        });

        if (this.and != null && !this.and.isEmpty())
        {
            final List<GatewayPredicate> andChildren = new ArrayList<>(this.and.size());
            for (int i = 0; i < this.and.size(); i++)
            {
                final ValidationResult nestedResult = validationResult.nested("and[" + i + "]");
                // We no longer need try-catch here because the child's build()
                // method internally handles its own exceptions now.
                andChildren.add(this.and.get(i).build(registry, nestedResult));
            }

            if (andChildren.size() == 1)
            {
                list.add(andChildren.getFirst());
            }
            else
            {
                list.add(new AndPredicate(andChildren));
            }
        }

        if (this.or != null && !this.or.isEmpty())
        {
            final List<GatewayPredicate> orChildren = new ArrayList<>(this.or.size());
            for (int i = 0; i < this.or.size(); i++)
            {
                final ValidationResult nestedResult = validationResult.nested("or[" + i + "]");
                orChildren.add(this.or.get(i).build(registry, nestedResult));
            }
            list.add(new OrPredicate(orChildren));
        }

        if (this.not != null)
        {
            final ValidationResult nestedResult = validationResult.nested("not");
            list.add(new NotPredicate(this.not.build(registry, nestedResult)));
        }

        // Optimize the evaluation tree
        if (list.isEmpty())
        {
            return TruePredicate.INSTANCE;
        }
        if (list.size() == 1)
        {
            return list.getFirst();
        }

        return new AndPredicate(list);
    }

    /**
     * Recursively validates that all requested predicates actually exist in the registry.
     */
    public void validateTree(final ValidationResult result, final PredicateRegistry registry)
    {
        validateTree(result, registry, true);
    }

    /**
     * True when the node names nothing to test. {@link #build} would turn it into an
     * always-true predicate, so an empty top-level {@code match} is a route that matches
     * nothing (reachable only as a fallback) and an empty node anywhere below it is refused.
     */
    public boolean isEmpty()
    {
        return predicates.isEmpty()
                && (and == null || and.isEmpty())
                && (or == null || or.isEmpty())
                && not == null;
    }

    private void validateTree(final ValidationResult result, final PredicateRegistry registry, final boolean topLevel)
    {
        // Below the top, an empty node used to build to true: 'not: {}' or '- {}' made the
        // route a catch-all, and 'or: []' silently dropped out of the tree.
        if (!topLevel)
        {
            if (isEmpty())
            {
                result.addError("match", "Empty condition: name at least one predicate, or remove it");
                return;
            }
        }
        if (and != null && and.isEmpty() && !(topLevel && isEmpty()))
        {
            result.addError("match", "Empty 'and': name at least one predicate, or remove it");
        }
        if (or != null && or.isEmpty())
        {
            result.addError("match", "Empty 'or': name at least one predicate, or remove it");
        }

        // Check flat leaf nodes
        predicates.keySet().forEach(name ->
        {
            if (!registry.exists(name))
            {
                result.addError("match", "Unknown matcher type: '" + name + "'");
            }
        });

        // Recurse down the logical branches
        if (and != null)
        {
            and.forEach(c -> c.validateTree(result, registry, false));
        }

        if (or != null)
        {
            or.forEach(c -> c.validateTree(result, registry, false));
        }

        if (not != null)
        {
            not.validateTree(result, registry, false);
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