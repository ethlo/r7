package com.ethlo.r7.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.SchedulerAware;
import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.predicates.PredicateRegistry;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.FilterRegistry;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
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
    private final GatewayScheduler scheduler;

    public ConfigurationManager(GatewayScheduler scheduler)
    {
        this.scheduler = scheduler;
        this.filterRegistry = new FilterRegistry();
        this.predicateRegistry = new PredicateRegistry(mapper);
    }

    public static ValidationResult validate(ValidatableConfig config)
    {
        final ValidationResult validationResult = new ValidationResult();
        config.validate(validationResult);
        return validationResult;
    }

    public void load(RoutesDefinition config, RouteRegistry routeRegistry)
    {
        final ValidationResult validationResult = validate(config);
        validationResult.throwIfInvalid();

        final List<GatewayRoute> routes = config.routes().stream()
                .map(routeDefinition ->
                {
                    // Load global filters
                    final List<GatewayFilter> globalFilters = new ArrayList<>();
                    for (final FilterDefinition filterDef : config.globalFilters())
                    {
                        instantiateFilters(validationResult, globalFilters, filterDef);
                    }

                    final List<GatewayFilter> filters = new ArrayList<>(globalFilters);
                    if (routeDefinition.filters() != null)
                    {
                        for (final FilterDefinition filterDef : routeDefinition.filters())
                        {
                            instantiateFilters(validationResult, filters, filterDef);
                        }
                    }

                    final RouteJournalConfig journalConfig = createJournalConfig(routeDefinition.journal());

                    // Validate the structure and the plugin names
                    if (routeDefinition.match() != null)
                    {
                        routeDefinition.match().validateTree(validationResult, predicateRegistry);
                    }

                    final GatewayPredicate predicate = routeDefinition.match().build(predicateRegistry);

                    if (routeDefinition.upstream().targets() == null)
                    {
                        new ValidatorUtils(validationResult).invalid(routeDefinition.id().toString(), "upstream.targets", null, "upstream targets required");
                        validationResult.throwIfInvalid();
                    }

                    final List<CharSequence> urls = routeDefinition.upstream().targets().stream().map(TargetConfig::url).map(CharSequence.class::cast).toList();
                    return (GatewayRoute) new DefaultGatewayRoute(urls, predicate, filters, journalConfig, routeDefinition);
                })
                .toList();

        routeRegistry.updateRoutes(config.version(), routes);
    }

    private RouteJournalConfig createJournalConfig(JournalDefinition definition)
    {
        return new RouteJournalConfig(
                new JournalDirectionConfig(definition.request().level(), JournalOverrideParser.parseOverrides(definition.request().statusOverrides())),
                new JournalDirectionConfig(definition.response().level(), JournalOverrideParser.parseOverrides(definition.response().statusOverrides()))
        );
    }

    private void instantiateFilters(final ValidationResult validationResult, final List<GatewayFilter> instantiatedFilters, final FilterDefinition filterDef)
    {
        final GatewayFilterFactory<ValidatableConfig> typedFactory = filterRegistry.get(filterDef.name());
        if (typedFactory instanceof SchedulerAware schedulerAware)
        {
            schedulerAware.setScheduler(scheduler);
        }

        final ValidatableConfig c = typedFactory.configClass() != null ? mapper.convertValue(filterDef.args(), typedFactory.configClass()) : new GatewayFilterFactory.EmptyConfig();
        c.validate(validationResult);
        validationResult.throwIfInvalid();
        instantiatedFilters.add(typedFactory.create(c));
    }

    public <T> T load(Path yamlFile, Class<T> type)
    {
        final String contents;
        try
        {
            contents = Files.readString(yamlFile);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        final String interpolated = EnvInterpolator.interpolate(contents);
        return mapper.readValue(interpolated, type);
    }
}