package com.ethlo.venturi.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.core.predicates.VenturiPredicates;

public class PredicateRegistry
{
    private static final Map<String, Function<Object, GatewayPredicate>> factories = new HashMap<>();

    static
    {
        register("method", val -> VenturiPredicates.method((List<CharSequence>) val));
        register("pathStartsWith", val -> VenturiPredicates.pathStartsWith(val.toString()));
        register("header", val -> {
                    final Map<String, String> map = (Map<String, String>) val;
                    return VenturiPredicates.headerMatches(map.get("name"), map.get("value"));
                }
        );
    }

    public static void register(String name, Function<Object, GatewayPredicate> factory)
    {
        factories.put(name, factory);
    }

    public static GatewayPredicate create(String name, Object value)
    {
        return factories.get(name).apply(value);
    }

    public static boolean exists(String name)
    {
        return factories.containsKey(name);
    }
}