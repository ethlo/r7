package com.ethlo.venturi.config;

import java.util.Map;

public record FilterDefinition(String type, Map<String, String> args)
{
}
