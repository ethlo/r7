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
    private final ObjectMapper mapper; // Ensure this is configured for Jackson 3!

    public PredicateRegistry(ObjectMapper mapper)
    {
        this.mapper = mapper;

        // Load plugins exactly once
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

        // 1. Map the raw YAML data to the specific Config record
        ValidatableConfig config = mapper.convertValue(yamlValue, factory.configClass());

        // 2. Validate the specific predicate configuration
        ValidationResult result = new ValidationResult();
        config.validate(result);
        result.throwIfInvalid();

        // 3. Instantiate the hot-path leaf node
        @SuppressWarnings("unchecked")
        GatewayPredicateFactory<ValidatableConfig> typedFactory =
                (GatewayPredicateFactory<ValidatableConfig>) factory;

        return typedFactory.create(config);
    }
}