package com.ethlo.venturi.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.core.ExecutableRoute;
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

    public VenturiLoader()
    {
        this.filterRegistry = new FilterRegistry();
    }

    public static ObjectMapper getMapper()
    {
        return mapper;
    }

    public static <T> T convertValue(Map<String, String> args, Class<T> configType)
    {
        return mapper.convertValue(args, configType);
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
                    for (final FilterDefinition filterDefinition : def.filters())
                    {
                        final GatewayFilterFactory factory = filterRegistry.get(filterDefinition.type());
                        final Map<String, String> configData = filterDefinition.args();
                        final ValidatableConfig filterConfig = (ValidatableConfig) mapper.convertValue(configData, factory.configClass());
                        instantiatedFilters.add(factory.create(filterConfig));
                    }

                    final GatewayRoute dataRoute = new DefaultGatewayRoute(def.id(), def.uri(), def.match().build(), instantiatedFilters);
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