package com.ethlo.venturi.core.predicates;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRequest;
import java.util.regex.Pattern;

public final class RegexPathPredicate implements GatewayPredicate {
    private final Pattern pattern;

    public RegexPathPredicate(String regex) {
        // Pre-compiling is mandatory for high-performance predicates
        this.pattern = Pattern.compile(regex);
    }

    @Override
    public boolean test(GatewayRequest req) {
        // This engages the regex engine for every single request
        return pattern.matcher(req.path()).matches();
    }
}