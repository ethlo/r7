package com.ethlo.venturi.core.predicates;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRequest;

public record HeaderMatchesPredicate(String name, String value) implements GatewayPredicate
{
    @Override
    public boolean test(GatewayRequest request)
    {
        final Iterable<CharSequence> headerValues = request.headers().getAll(name);

        if (headerValues == null)
        {
            return false;
        }

        for (CharSequence val : headerValues)
        {
            if (value.contentEquals(val))
            {
                return true;
            }
        }
        return false;
    }
}