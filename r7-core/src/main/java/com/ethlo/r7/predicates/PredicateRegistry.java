package com.ethlo.r7.predicates;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.config.ConfigurationException;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

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
        final GatewayPredicateFactory<?> factory = factories.get(name);
        if (factory == null)
        {
            throw new ConfigurationException("Unknown predicate: " + name);
        }

        // Load config and map to config class
        ValidatableConfig config;
        try
        {
            config = mapper.convertValue(yamlValue, factory.configClass());
        }
        catch (UnrecognizedPropertyException e)
        {
            final String badProp = e.getPropertyName();
            final String knownProps = e.getKnownPropertyIds() != null
                    ? e.getKnownPropertyIds().toString()
                    : "none";

            throw new ConfigurationException(String.format("Unrecognized property '%s'. Known properties are: %s", badProp, knownProps));
        }

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