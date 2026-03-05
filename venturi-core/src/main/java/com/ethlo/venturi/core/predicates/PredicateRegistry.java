package com.ethlo.venturi.core.predicates;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.spi.GatewayPredicateFactory;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;
import tools.jackson.databind.ObjectMapper;

public class PredicateRegistry
{
    private final Map<String, GatewayPredicateFactory<?>> factories;
    private final ObjectMapper mapper;

    public PredicateRegistry(ObjectMapper mapper)
    {
        this.mapper = mapper;
        this.factories = ServiceLoader.load(GatewayPredicateFactory.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .collect(Collectors.toUnmodifiableMap(
                        GatewayPredicateFactory::name,
                        factory -> factory
                ));
    }

    public boolean exists(String name)
    {
        return factories.containsKey(name);
    }

    public GatewayPredicate create(String name, Object yamlValue)
    {
        GatewayPredicateFactory<?> factory = factories.get(name);
        if (factory == null)
        {
            throw new IllegalArgumentException("Unknown predicate: " + name);
        }

        // Load config and map to config class
        ValidatableConfig config = mapper.convertValue(yamlValue, factory.configClass());

        // Validate the configuration
        final ValidationResult result = new ValidationResult();
        config.validate(result);
        result.throwIfInvalid();

        // Instantiate predicate with config
        @SuppressWarnings("unchecked")
        GatewayPredicateFactory<ValidatableConfig> typedFactory = (GatewayPredicateFactory<ValidatableConfig>) factory;
        return typedFactory.create(config);
    }
}