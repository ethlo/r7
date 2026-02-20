package com.ethlo.venturi.core.predicates;

import java.util.List;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.util.CharSequenceUtil;

public record HostPredicate(List<String> hosts) implements GatewayPredicate
{
    @Override
    public boolean test(GatewayRequest request)
    {
        final CharSequence requestHost = request.headers().getFirst("host");
        if (requestHost == null)
        {
            return false;
        }

        // Find the port separator without creating a new string
        int colonIndex = -1;
        for (int i = 0; i < requestHost.length(); i++)
        {
            if (requestHost.charAt(i) == ':')
            {
                colonIndex = i;
                break;
            }
        }

        // Determine the length of the host part
        int hostLength = (colonIndex != -1) ? colonIndex : requestHost.length();

        for (String host : hosts)
        {
            if (CharSequenceUtil.equalsIgnoreCase(requestHost, host))
            {
                return true;
            }
        }
        return false;
    }
}