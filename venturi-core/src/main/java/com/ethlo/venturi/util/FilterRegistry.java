package com.ethlo.venturi.util;

import com.ethlo.venturi.spi.GatewayFilterFactory;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

public final class FilterRegistry
{
    private final Map<String, GatewayFilterFactory<?>> factories;

    public FilterRegistry()
    {
        // Load all factories from the classpath and modules exactly once
        ServiceLoader<GatewayFilterFactory> loader = ServiceLoader.load(GatewayFilterFactory.class);

        this.factories = loader.stream()
                .map(ServiceLoader.Provider::get)
                .collect(Collectors.toUnmodifiableMap(
                        GatewayFilterFactory::name,
                        factory -> factory,
                        (f1, f2) -> {
                            throw new IllegalStateException("Duplicate filter name: " + f1.name());
                        }
                ));
    }

    public GatewayFilterFactory get(String name)
    {
        GatewayFilterFactory factory = factories.get(name);
        if (factory == null)
        {
            throw new IllegalArgumentException("Unknown filter factory: " + name);
        }
        return factory;
    }
}