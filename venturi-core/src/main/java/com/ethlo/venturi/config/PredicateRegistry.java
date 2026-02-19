package com.ethlo.venturi.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.core.predicates.AndPredicate;
import com.ethlo.venturi.core.predicates.HeaderMatchesPredicate;
import com.ethlo.venturi.core.predicates.HostPredicate;
import com.ethlo.venturi.core.predicates.MethodPredicate;
import com.ethlo.venturi.core.predicates.NotPredicate;
import com.ethlo.venturi.core.predicates.OrPredicate;
import com.ethlo.venturi.core.predicates.PathStartsWithPredicate;

public class PredicateRegistry
{
    private static final Map<String, Function<Object, GatewayPredicate>> factories = new HashMap<>();

    static
    {
        register("method", val -> {
                    @SuppressWarnings("unchecked")
                    String[] methods = ((List<String>) val).stream().map(String::toUpperCase).toList().toArray(new String[0]);
                    return new MethodPredicate(methods);
                }
        );

        register("host", val -> {
                    if (val instanceof List<?> list)
                    {
                        return new HostPredicate(list.stream().map(Object::toString).toList());
                    }
                    return new HostPredicate(List.of(val.toString()));
                }
        );

        register("pathStartsWith", val -> new PathStartsWithPredicate(val.toString()));

        register("header", val -> {
                    @SuppressWarnings("unchecked") final Map<String, String> map = (Map<String, String>) val;
                    return new HeaderMatchesPredicate(map.get("name"), map.get("value"));
                }
        );

        // Logical Composites
        register("and", val -> {
                    @SuppressWarnings("unchecked")
                    List<GatewayPredicate> children = (List<GatewayPredicate>) val;
                    return new AndPredicate(children);
                }
        );

        register("or", val -> {
                    @SuppressWarnings("unchecked")
                    List<GatewayPredicate> children = (List<GatewayPredicate>) val;
                    return new OrPredicate(children);
                }
        );

        register("not", val -> new NotPredicate((GatewayPredicate) val));
    }

    public static void register(String name, Function<Object, GatewayPredicate> factory)
    {
        factories.put(name.toLowerCase(), factory);
    }

    public static GatewayPredicate create(String name, Object value)
    {
        Function<Object, GatewayPredicate> factory = factories.get(name.toLowerCase());
        if (factory == null)
        {
            throw new IllegalArgumentException("Unknown predicate: " + name);
        }
        return factory.apply(value);
    }

    public static boolean exists(String name)
    {
        return factories.containsKey(name.toLowerCase());
    }

    /**
     * Entry point for parsing the "match" section of a route definition
     */
    public static GatewayPredicate parse(Object matchDef)
    {
        if (matchDef == null)
        {
            return null; // Becomes "ALWAYS" in your printer
        }

        if (matchDef instanceof Map<?, ?> map)
        {
            final List<GatewayPredicate> predicates = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet())
            {
                String key = entry.getKey().toString();
                Object value = entry.getValue();

                // RECURSION: If the value is a list (like in and/or),
                // we need to parse each element before creating the composite
                Object processedValue = processValue(key, value);
                predicates.add(create(key, processedValue));
            }

            // If there are multiple top-level predicates in one map,
            // wrap them in an implicit 'And'
            return predicates.size() == 1 ? predicates.get(0) : new AndPredicate(predicates);
        }

        throw new IllegalArgumentException("Invalid match definition type: " + matchDef.getClass());
    }

    private static Object processValue(String key, Object value)
    {
        // Handle logical composites (and/or)
        if ((key.equalsIgnoreCase("and") || key.equalsIgnoreCase("or")) && value instanceof List<?> list)
        {
            return list.stream().map(PredicateRegistry::parse).toList();
        }

        // Handle logical NOT
        if (key.equalsIgnoreCase("not"))
        {
            return parse(value);
        }

        // It's a leaf node (path, method, etc.), return as is for the factory
        return value;
    }
}