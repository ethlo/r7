package com.ethlo.venturi;

import com.ethlo.venturi.api.GatewayPredicate;

public record PredicateConfig(String id,
                              GatewayPredicate predicate,
                              LogOptions request,
                              LogOptions response)
{

}
