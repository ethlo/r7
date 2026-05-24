package com.ethlo.r7.util;

import static com.ethlo.r7.util.Levenshtein.findClosestMatch;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

import com.ethlo.r7.config.ConfigurationException;
import com.ethlo.r7.spi.GatewayFilterFactory;

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

    public GatewayFilterFactory get(String filterName)
    {
        GatewayFilterFactory factory = factories.get(filterName);
        if (factory == null)
        {
            final List<String> available = factories.keySet().stream().sorted().toList();
            final String closestMatch = findClosestMatch(filterName, available);

            final String suggestion = closestMatch != null ? " Did you mean '" + closestMatch + "'?" : "";

            throw new ConfigurationException(
                    "Unknown filter: '" + filterName + "'." + suggestion +
                            " Available filters are: " + String.join(", ", available)
            );
        }
        return factory;
    }
}