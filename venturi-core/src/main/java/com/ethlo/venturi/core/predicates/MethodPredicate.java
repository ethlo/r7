package com.ethlo.venturi.core.predicates;

import java.util.Arrays;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.core.ShortInfo;
import com.ethlo.venturi.core.util.CharSequenceUtil;

public record MethodPredicate(String[] methods) implements GatewayPredicate, ShortInfo
{
    @Override
    public boolean test(GatewayRequest request)
    {
        final CharSequence requestMethod = request.method();
        for (final String m : methods)
        {
            if (CharSequenceUtil.equals(m, requestMethod))
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public String summary()
    {
        return "Method: " + Arrays.toString(methods);
    }
}