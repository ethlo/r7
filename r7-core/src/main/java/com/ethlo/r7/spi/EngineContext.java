package com.ethlo.r7.spi;

import java.util.Map;
import java.util.Optional;

public final class EngineContext
{
    private final Map<Class<?>, Object> services;

    public EngineContext(final Map<Class<?>, Object> services)
    {
        this.services = Map.copyOf(services); // Immutable for thread safety
    }

    public <T> Optional<T> get(final Class<T> type)
    {
        final Object instance = this.services.get(type);
        if (instance != null)
        {
            return Optional.of(type.cast(instance));
        }
        return Optional.empty();
    }

    public <T> T getRequired(final Class<T> type)
    {
        return this.get(type).orElseThrow(() -> 
            new IllegalStateException("Missing required gateway service: " + type.getName())
        );
    }
}