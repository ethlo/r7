package com.ethlo.venturi.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;

import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.core.ExecutableRoute;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

public final class VenturiLoader
{
    private final ObjectMapper mapper;
    private final FilterBuilder filterBuilder;

    public VenturiLoader()
    {
        this.mapper = YAMLMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        filterBuilder = new FilterBuilder();
    }

    /**
     * @param configurator Takes the raw RouteDefinition and returns a server-specific ExecutableRoute
     */
    public void load(Path yamlFile, RouteRegistry registry,
                     BiFunction<RouteDefinition, GatewayRoute, ExecutableRoute> configurator) throws IOException
    {
        final VenturiConfig config = load(yamlFile, VenturiConfig.class);
        final List<ExecutableRoute> routes = config.routes.stream()
                .map(def -> {

                    final List<GatewayFilter> instantiatedFilters = filterBuilder.resolve(def);
                    final GatewayRoute dataRoute = new DefaultGatewayRoute(def.id(), def.uri(), def.match().build(), instantiatedFilters);
                    return configurator.apply(def, dataRoute);
                })
                .toList();

        registry.updateRoutes(routes);
    }

    public <T> T load(Path yamlFile, Class<T> type)
    {
        return mapper.readValue(yamlFile, type);
    }
}