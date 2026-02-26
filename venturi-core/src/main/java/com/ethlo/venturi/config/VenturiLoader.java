package com.ethlo.venturi.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.core.ExecutableRoute;
import com.ethlo.venturi.core.predicates.PredicateRegistry;
import com.ethlo.venturi.plugin.FilterRegistry;
import com.ethlo.venturi.spi.GatewayFilterFactory;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

public final class VenturiLoader
{
    private static final ObjectMapper mapper;

    static
    {
        mapper = YAMLMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    private final FilterRegistry filterRegistry;
    private final PredicateRegistry predicateRegistry;

    public VenturiLoader()
    {
        this.filterRegistry = new FilterRegistry();
        this.predicateRegistry = new PredicateRegistry(mapper);
    }

    /**
     * @param configurator Takes the raw RouteDefinition and returns a server-specific ExecutableRoute
     */
    public void load(Path yamlFile, RouteRegistry routeRegistry,
                     BiFunction<RouteDefinition, GatewayRoute, ExecutableRoute> configurator) throws IOException
    {

        final RoutesConfig config = load(yamlFile, RoutesConfig.class);
        final ValidationResult validationResult = validate(config);
        validationResult.throwIfInvalid();
        final List<ExecutableRoute> routes = config.routes.stream()
                .map(def -> {
                    final List<GatewayFilter> instantiatedFilters = new ArrayList<>();
                    for (final FilterDefinition filterDef : def.filters())
                    {
                        // 1. Ask the registry for the factory by name (e.g., "AddResponseHeader")
                        final GatewayFilterFactory<?> factory = filterRegistry.get(filterDef.name());
                        if (factory == null)
                        {
                            validationResult.addError("filters", "Unknown filter type: " + filterDef.name());
                            continue;
                        }

                        // 2. Jackson perfectly maps the raw 'args' Object to the specific Record
                        final ValidatableConfig c = mapper.convertValue(filterDef.args(), factory.configClass());

                        // 3. Validate and Build
                        c.validate(validationResult);

                        @SuppressWarnings("unchecked") final GatewayFilterFactory<ValidatableConfig> typedFactory =
                                (GatewayFilterFactory<ValidatableConfig>) factory;

                        instantiatedFilters.add(typedFactory.create(c));
                    }

                    // Validate the structure and the plugin names
                    def.match().validateTree(validationResult, predicateRegistry);

                    final GatewayPredicate predicate = def.match().build(predicateRegistry);

                    final GatewayRoute dataRoute = new DefaultGatewayRoute(def.id(), def.uri(), predicate, instantiatedFilters);
                    return configurator.apply(def, dataRoute);
                })
                .toList();

        routeRegistry.updateRoutes(routes);
    }

    private ValidationResult validate(RoutesConfig config)
    {
        final ValidationResult validationResult = new ValidationResult();
        for (RouteDefinition route : config.routes)
        {
            route.validate(validationResult);
        }
        return validationResult;
    }

    public <T> T load(Path yamlFile, Class<T> type)
    {
        return mapper.readValue(yamlFile, type);
    }
}