package com.ethlo.venturi.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.predicates.PredicateRegistry;
import com.ethlo.venturi.spi.GatewayFilterFactory;
import com.ethlo.venturi.util.FilterRegistry;
import com.ethlo.venturi.util.ValidatorUtils;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

public final class ConfigurationManager
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

    public ConfigurationManager()
    {
        this.filterRegistry = new FilterRegistry();
        this.predicateRegistry = new PredicateRegistry(mapper);
    }

    public static ValidationResult validate(ValidatableConfig config)
    {
        final ValidationResult validationResult = new ValidationResult();
        config.validate(validationResult);
        return validationResult;
    }

    public void load(RoutesConfig config, RouteRegistry routeRegistry)
    {
        final ValidationResult validationResult = validate(config);
        validationResult.throwIfInvalid();
        final List<GatewayRoute> routes = config.routes().stream()
                .map(def -> {
                    final List<GatewayFilter> instantiatedFilters = new ArrayList<>();
                    if (def.filters() != null)
                    {
                        for (final FilterDefinition filterDef : def.filters())
                        {
                            final GatewayFilterFactory<ValidatableConfig> typedFactory = filterRegistry.get(filterDef.name());
                            final ValidatableConfig c = typedFactory.configClass() != null ? mapper.convertValue(filterDef.args(), typedFactory.configClass()) : new GatewayFilterFactory.EmptyConfig();
                            c.validate(validationResult);

                            instantiatedFilters.add(typedFactory.create(c));
                        }
                    }

                    // Validate the structure and the plugin names
                    if (def.match() != null)
                    {
                        def.match().validateTree(validationResult, predicateRegistry);
                    }

                    final GatewayPredicate predicate = def.match().build(predicateRegistry);

                    if (def.upstream().targets() == null)
                    {
                        new ValidatorUtils(validationResult).invalid(def.id().toString(), "upstream.targets", null, "upstream targets required");
                        validationResult.throwIfInvalid();
                    }
                    final List<CharSequence> urls = def.upstream().targets().stream().map(TargetConfig::url).map(CharSequence.class::cast).toList();
                    return (GatewayRoute) new DefaultGatewayRoute(def.id(), urls, predicate, instantiatedFilters, def);
                })
                .toList();

        routeRegistry.updateRoutes(routes);
    }

    public <T> T load(Path yamlFile, Class<T> type)
    {
        return mapper.readValue(yamlFile, type);
    }
}