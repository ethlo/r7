package com.ethlo.venturi.config;

import java.util.List;

public record RouteDefinition(CharSequence id, List<CharSequence> uri, ConditionDefinition match, AuditDefinition audit,
                              List<FilterDefinition> filters)
{

}