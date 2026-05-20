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
import com.ethlo.r7.predicates.PredicateRegistry;
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

    public GatewayPredicate build(final PredicateRegistry registry)
    {
        final List<GatewayPredicate> list = new ArrayList<>();

        this.predicates.forEach((name, value) ->
        {
            try
            {
                list.add(registry.create(name, value));
            }
            catch (final ConfigurationException e)
            {
                // Inject the predicate name into the error path
                throw new ConfigurationException(String.format("[%s] %s", name, e.getMessage()));
            }
        });

        if (this.and != null && !this.and.isEmpty())
        {
            final List<GatewayPredicate> andChildren = new ArrayList<>(this.and.size());
            for (int i = 0; i < this.and.size(); i++)
            {
                try
                {
                    andChildren.add(this.and.get(i).build(registry));
                }
                catch (final ConfigurationException e)
                {
                    throw new ConfigurationException(String.format("[and[%d]] %s", i, e.getMessage()));
                }
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
                try
                {
                    orChildren.add(this.or.get(i).build(registry));
                }
                catch (final ConfigurationException e)
                {
                    throw new ConfigurationException(String.format("[or[%d]] %s", i, e.getMessage()));
                }
            }
            list.add(new OrPredicate(orChildren));
        }

        if (this.not != null)
        {
            try
            {
                list.add(new NotPredicate(this.not.build(registry)));
            }
            catch (final ConfigurationException e)
            {
                throw new ConfigurationException(String.format("[not] %s", e.getMessage()));
            }
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
            parts.add("NOT(" + this.not.toString() + ")");
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
            return "MATCH_ALL";
        }

        if (parts.size() == 1)
        {
            return parts.getFirst();
        }

        return "AND(" + String.join(", ", parts) + ")";
    }
}