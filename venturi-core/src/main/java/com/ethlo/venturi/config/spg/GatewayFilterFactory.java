package com.ethlo.venturi.config.spg;

import java.util.Map;

import com.ethlo.venturi.api.GatewayFilter;

public interface GatewayFilterFactory
{
    String getName();

    GatewayFilter create(Map<String, String> args);
}
