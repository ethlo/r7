package com.ethlo.venturi.core.predicates;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.core.ShortInfo;

public record PathStartsWithPredicate(String prefix) implements GatewayPredicate, ShortInfo
{
    @Override
    public boolean test(GatewayRequest request)
    {
        return request.path().toString().startsWith(prefix);
    }

    @Override
    public String summary()
    {
        return "PathStartsWith: " + prefix;
    }
}