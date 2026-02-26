package com.ethlo.venturi.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.core.predicates.VenturiPredicates;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;
import com.fasterxml.jackson.annotation.JsonCreator;

public final class ConditionDefinition implements ValidatableConfig
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
            // Case: match: [ {path: /a}, {method: GET} ]
            // Treat as an implicit AND
            def.and = list.stream()
                    .map(ConditionDefinition::create)
                    .toList();
        }
        else if (raw instanceof Map<?, ?> map)
        {
            // Case: match: { pathStartsWith: /hello }
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                switch (key)
                {
                    case "and" -> def.and = createList(v);
                    case "or" -> def.or = createList(v);
                    case "not" -> def.not = create(v);
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

    public GatewayPredicate build()
    {
        final List<GatewayPredicate> list = new ArrayList<>();

        // Process Flat Predicates (Implicit AND)
        predicates.forEach((name, value) ->
                list.add(PredicateRegistry.create(name, value)));

        // Process Logical Groups
        if (and != null)
        {
            and.forEach(c -> list.add(c.build()));
        }

        if (not != null)
        {
            // TODO: Support not
            //list.add(VenturiPredicates.not(not.build()));
        }

        // Handle 'or' specifically since it's a disjunction
        if (or != null && !or.isEmpty())
        {
            final List<GatewayPredicate> children = or.stream()
                    .map(ConditionDefinition::build)
                    .toList();
            list.add(VenturiPredicates.or(children));
        }

        // Return optimized single or composite AND
        if (list.size() == 1)
        {
            return list.getFirst();
        }

        return VenturiPredicates.and(list);
    }

    @Override
    public void validate(final ValidationResult result)
    {
        predicates.keySet().forEach(name ->
        {
            if (!PredicateRegistry.exists(name))
            {
                result.addError("match." + name, "Unknown matcher type: " + name);
            }
        });

        if (and != null)
        {
            and.forEach(c -> c.validate(result));
        }

        if (or != null)
        {
            or.forEach(c -> c.validate(result));
        }

        if (not != null)
        {
            not.validate(result);
        }
    }
}