package com.ethlo.venturi.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;

import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.core.ExecutableRoute;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

public final class VenturiLoader
{
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    private final FilterBuilder filterBuilder = new FilterBuilder();

    /**
     * @param configurator Takes the raw RouteDefinition and returns a server-specific ExecutableRoute
     */
    public void load(Path yamlFile, RouteRegistry registry,
                     BiFunction<RouteDefinition, GatewayRoute, ExecutableRoute> configurator) throws IOException
    {

        final VenturiConfig config = mapper.readValue(yamlFile.toFile(), VenturiConfig.class);

        final List<ExecutableRoute> routes = config.routes.stream()
                .map(def -> {

                    final List<GatewayFilter> instantiatedFilters = def.filters().stream()
                            .map(filterBuilder::resolveFilter)
                            .toList();
                    // 1. Build the API-level data route
                    final GatewayRoute dataRoute = new DefaultGatewayRoute(def.id(), def.uri(), def.match().build(), instantiatedFilters);

                    // 2. Use the provided function to "upgrade" it to an ExecutableRoute
                    return configurator.apply(def, dataRoute);
                })
                .toList();

        registry.updateRoutes(routes);
    }
}