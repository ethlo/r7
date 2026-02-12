package com.ethlo.venturi.config;

import java.util.List;

public record RouteDefinition(CharSequence id, CharSequence uri, ConditionDefinition match, AuditDefinition audit,
                              List<FilterDefinition> filters)
{

}