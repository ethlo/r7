package com.ethlo.venturi.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.core.predicates.AndPredicate;
import com.ethlo.venturi.core.predicates.NotPredicate;
import com.ethlo.venturi.core.predicates.OrPredicate;
import com.ethlo.venturi.core.predicates.PredicateRegistry;
import com.ethlo.venturi.validation.ValidationResult;
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

    public GatewayPredicate build(com.ethlo.venturi.core.predicates.PredicateRegistry registry)
    {
        final List<GatewayPredicate> list = new ArrayList<>();

        // 1. Process Flat Predicates (Implicit AND)
        // e.g., pathStartsWith: /hello
        predicates.forEach((name, value) ->
                list.add(registry.create(name, value)));

        // 2. Process Logical Groups
        if (and != null && !and.isEmpty())
        {
            final List<GatewayPredicate> andChildren = and.stream()
                    .map(c -> c.build(registry))
                    .toList();

            if (andChildren.size() == 1)
            {
                list.add(andChildren.getFirst());
            }
            else
            {
                list.add(new AndPredicate(andChildren));
            }
        }

        if (or != null && !or.isEmpty())
        {
            final List<GatewayPredicate> orChildren = or.stream()
                    .map(c -> c.build(registry))
                    .toList();
            list.add(new OrPredicate(orChildren));
        }

        if (not != null)
        {
            // Fully supported NotPredicate wrapper
            list.add(new NotPredicate(not.build(registry)));
        }

        // 3. Optimize the evaluation tree
        if (list.isEmpty())
        {
            // If the match block is completely empty, it matches everything
            return request -> true;
        }
        if (list.size() == 1)
        {
            // Strip the unnecessary AND wrapper if there's only one root condition
            return list.getFirst();
        }

        // At the root level of a YAML block, siblings are evaluated as an AND.
        // e.g., match: { method: GET, pathStartsWith: /api } 
        return new AndPredicate(list);
    }

    /**
     * Recursively validates that all requested predicates actually exist in the registry.
     */
    public void validateTree(final ValidationResult result, final PredicateRegistry registry)
    {
        // 1. Check flat leaf nodes
        predicates.keySet().forEach(name ->
        {
            if (!registry.exists(name))
            {
                result.addError("match", "Unknown matcher type: '" + name + "'");
            }
        });

        // 2. Recurse down the logical branches
        if (and != null)
        {
            and.forEach(c -> c.validateTree(result, registry));
        }

        if (or != null)
        {
            or.forEach(c -> c.validateTree(result, registry));
        }

        if (not != null)
        {
            not.validateTree(result, registry);
        }
    }
}