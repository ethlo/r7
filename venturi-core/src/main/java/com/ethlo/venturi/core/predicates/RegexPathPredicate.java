package com.ethlo.venturi.core.predicates;

import java.util.regex.Pattern;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRequest;

public final class RegexPathPredicate implements GatewayPredicate
{
    private final Pattern pattern;

    public RegexPathPredicate(String regex)
    {
        // Pre-compiling is mandatory for high-performance predicates
        this.pattern = Pattern.compile(regex);
    }

    @Override
    public boolean test(GatewayRequest request)
    {
        return pattern.matcher(request.uri()).matches();
    }
}