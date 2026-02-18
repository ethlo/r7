package com.ethlo.venturi.core.predicates;

import java.util.Arrays;
import java.util.List;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.core.ShortInfo;

public record MethodPredicate(List<String> methods) implements GatewayPredicate, ShortInfo
{
    @Override
    public boolean test(GatewayRequest request)
    {
        final String requestMethod = request.method().toString();
        return methods.stream().anyMatch(m -> m.equalsIgnoreCase(requestMethod));
    }

    @Override
    public String summary()
    {
        return "Method: " + Arrays.toString(methods.toArray());
    }
}