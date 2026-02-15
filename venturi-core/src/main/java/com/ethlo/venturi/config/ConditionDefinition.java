package com.ethlo.venturi.config;

import java.util.List;
import java.util.stream.Collectors;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.core.predicates.VenturiPredicates;

public final class ConditionDefinition
{
    public List<CharSequence> method;
    public String pathStartsWith;
    public HeaderCondition header;
    public List<ConditionDefinition> and;
    public List<ConditionDefinition> or;
    public ConditionDefinition not;

    public GatewayPredicate build()
    {
        // Logical Operators
        if (and != null && !and.isEmpty())
        {
            final List<GatewayPredicate> children = and.stream()
                    .map(ConditionDefinition::build)
                    .collect(Collectors.toList());
            return VenturiPredicates.and(children);
        }

        if (or != null && !or.isEmpty())
        {
            final List<GatewayPredicate> children = or.stream()
                    .map(ConditionDefinition::build)
                    .collect(Collectors.toList());
            return VenturiPredicates.or(children);
        }

        if (not != null)
        {
            final GatewayPredicate inner = not.build();
            return exchange -> !inner.test(exchange);
        }

        // Matchers using optimized CharSequence logic
        if (method != null)
        {
            return VenturiPredicates.method(method);
        }
        if (pathStartsWith != null)
        {
            return VenturiPredicates.pathStartsWith(pathStartsWith);
        }
        if (header != null)
        {
            return VenturiPredicates.headerMatches(header.name, header.value);
        }

        return exchange -> true;
    }

    public static final class HeaderCondition
    {
        public String name;
        public String value;
    }
}