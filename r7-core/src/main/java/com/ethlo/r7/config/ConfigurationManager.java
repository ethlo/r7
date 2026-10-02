package com.ethlo.r7.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.config.model.HttpStatus;
import com.ethlo.r7.spi.EngineContext;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.FilterRegistry;
import com.ethlo.r7.util.PredicateRegistry;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.dataformat.yaml.JacksonYAMLParseException;

public final class ConfigurationManager
{
    /**
     * The {@link FilterCreationContext#routeId()} global filters are created under: they belong
     * to no single route, and no filter factory currently reads this value, but a real route ID
     * would misleadingly claim ownership by whichever route happened to build first.
     */
    private static final String GLOBAL_FILTER_ROUTE_ID = "<global>";

    private static final Logger log = LoggerFactory.getLogger(ConfigurationManager.class);

    private static final ObjectMapper mapper;

    static
    {
        // Layers the one gateway-specific deserializer (routes.yaml's HTTP status fields) on
        // top of the shared, engine-agnostic YAML conventions every r7 config file uses.
        final SimpleModule r7Module = new SimpleModule();
        r7Module.addDeserializer(HttpStatus.class, new HttpStatusDeserializer());

        mapper = YamlConfigSupport.baseMapperBuilder()
                .addModule(r7Module)
                .build();
    }

    // =========================================================
    // Custom Deserializers
    // =========================================================

    private final FilterRegistry filterRegistry;
    private final PredicateRegistry predicateRegistry;
    private final EngineContext engineContext;

    public ConfigurationManager(EngineContext engineContext)
    {
        this.engineContext = engineContext;
        this.filterRegistry = new FilterRegistry();
        this.predicateRegistry = new PredicateRegistry(mapper);
    }

    public static <T> T load(Path yamlFile, Class<T> type)
    {
        return YamlConfigSupport.load(mapper, yamlFile, type);
    }

    /**
     * Loads a routes file as {@link #load(Path, Class)} does, and logs a warning for each value
     * that is valid but almost certainly not what was meant: see {@link RegexLookalikeCheck}.
     */
    public RoutesDefinition loadRoutes(final Path yamlFile)
    {
        final RegexLookalikeCheck check = new RegexLookalikeCheck(this.predicateRegistry);
        return YamlConfigSupport.load(mapper, yamlFile, RoutesDefinition.class, (root, positions) ->
                check.check(root, positions).forEach(warning -> log.warn("{} {}", yamlFile, warning)));
    }

    public void load(RoutesDefinition config, RouteRegistry routeRegistry)
    {
        routeRegistry.publish(this.build(config));
    }

    /**
     * Validates and instantiates the routes and the unrouted policy without publishing them.
     */
    public RouteRegistry.Snapshot build(RoutesDefinition config)
    {
        final ValidationResult validationResult = new ValidationResult();

        if (config.routes() == null || config.routes().isEmpty())
        {
            throw new ConfigurationException("No routes defined");
        }

        // 1. Structural Syntax Pass - Validate every individual object
        config.validate(validationResult.nested("routes"));

        // 2. Semantic Analysis Pass - Check for collisions and dangling references
        validateUniqueRouteIds(config.routes());
        validateCrossRouteReferences(config.routes());
        validationResult.throwIfInvalid(); // Fail before any instantiation

        // 3. Transformation Pass: Only now, when we know the map is sane, do we instantiate.
        // Global filters are instantiated exactly once, outside the per-route loop, and the
        // same filter instances are then prepended to every route: a global RateLimiter or
        // CircuitBreaker must see every request against one shared bucket/state, not a fresh
        // instance - and its private cache - per route it happens to be wired onto.
        final List<GatewayFilter> globalFilters = new ArrayList<>();
        for (final FilterDefinition filterDef : config.globalFilters())
        {
            instantiateFilters(new FilterCreationContext(GLOBAL_FILTER_ROUTE_ID, engineContext), validationResult, globalFilters, filterDef);
        }

        final List<GatewayRoute> routes = config.routes().stream()
                .map(routeDefinition ->
                {
                    final FilterCreationContext filterCreationContext = new FilterCreationContext(routeDefinition.id(), engineContext);

                    final List<GatewayFilter> filters = new ArrayList<>(globalFilters);
                    if (routeDefinition.filters() != null)
                    {
                        for (final FilterDefinition filterDef : routeDefinition.filters())
                        {
                            instantiateFilters(filterCreationContext, validationResult, filters, filterDef);
                        }
                    }

                    final RouteJournalConfig journalConfig = createJournalConfig(routeDefinition.journal(),
                            validationResult.nested("routes").nested(routeDefinition.id()).nested("journal"));

                    // Validate the structure and the plugin names
                    GatewayPredicate predicate = FalsePredicate.INSTANCE;
                    if (routeDefinition.match() != null)
                    {
                        routeDefinition.match().validateTree(validationResult, predicateRegistry);

                        try
                        {
                            predicate = routeDefinition.match().build(predicateRegistry, validationResult);
                        }
                        catch (final ConfigurationException e)
                        {
                            // Grab the route ID for the breadcrumb, fallback to 'unknown' if not set yet
                            final String routeId = routeDefinition.id();
                            throw new ConfigurationException(String.format("[routes.%s.match] %s", routeId, e.getMessage()));
                        }
                    }

                    final List<TargetConfig> upstreamTargets = routeDefinition.upstream() != null ? routeDefinition.upstream().targets() : List.of();
                    if (routeDefinition.upstream() != null && upstreamTargets == null)
                    {
                        new ValidatorUtils(validationResult).invalid("targets", null, "upstream targets required");
                        validationResult.throwIfInvalid();
                    }

                    final List<String> urls = upstreamTargets.stream().map(TargetConfig::url).toList();
                    return (GatewayRoute) new DefaultGatewayRoute(urls, predicate, filters, globalFilters.size(), journalConfig, routeDefinition);
                })
                .toList();

        // Predicate/filter instantiation (step 3) reports its errors onto the same
        // validationResult rather than throwing, so a poisoned predicate (e.g. an unknown
        // matcher wrapped in 'not:') would otherwise load as whatever fallback the tree built -
        // silently, and possibly inverted to match everything. Fail closed here instead.
        validationResult.throwIfInvalid();

        return new RouteRegistry.Snapshot(config.version(), routes, createUnroutedRoute(config.unrouted()));
    }

    private void validateUniqueRouteIds(List<RouteDefinition> routes)
    {
        final Set<String> seen = new HashSet<>();
        routes.forEach(r -> {
            if (!seen.add(r.id()))
            {
                throw new ConfigurationException(String.format("[routes.%s] id is not unique", r.id()));
            }
        });
    }

    private void validateCrossRouteReferences(final List<RouteDefinition> routes)
    {
        for (final RouteDefinition r : routes)
        {
            if (r.upstream() != null && r.upstream().fallback() != null)
            {
                final FallbackConfig fallbackRoute = r.upstream().fallback();
                final String fallbackRouteId = fallbackRoute.routeId();
                if (fallbackRouteId != null && !fallbackRouteId.isBlank())
                {
                    // Prevent infinite loops
                    if (fallbackRouteId.equals(r.id()))
                    {
                        throw new ConfigurationException("Configuration error: Route '" + r.id() + "' specifies itself as its fallback.route_id.");
                    }

                    // Ensure the fallback route actually exists in the registry
                    if (routes.stream().noneMatch(e -> e.id().equals(fallbackRouteId)))
                    {
                        throw new ConfigurationException("Configuration error: Route '" + r.id() + "' references a fallback.route_id '" + fallbackRouteId + "' that does not exist.");
                    }
                }
            }
        }
        validateAcyclicFallbacks(routes);
    }

    /**
     * A fallback route is run as if the request had matched it, including its own fallback, so
     * a cycle such as a -> b -> a would recurse until the stack overflows the moment every
     * upstream in it is down.
     */
    private void validateAcyclicFallbacks(final List<RouteDefinition> routes)
    {
        final Map<String, String> fallbackOf = new HashMap<>();
        for (final RouteDefinition r : routes)
        {
            if (r.upstream() != null && r.upstream().fallback() != null)
            {
                final String target = r.upstream().fallback().routeId();
                if (target != null && !target.isBlank())
                {
                    fallbackOf.put(r.id(), target);
                }
            }
        }

        for (final String start : fallbackOf.keySet())
        {
            final List<String> chain = new ArrayList<>();
            String current = start;
            while (current != null)
            {
                if (chain.contains(current))
                {
                    chain.add(current);
                    throw new ConfigurationException("Configuration error: fallback.route_id references form a cycle: " + String.join(" -> ", chain.subList(chain.indexOf(current), chain.size())));
                }
                chain.add(current);
                current = fallbackOf.get(current);
            }
        }
    }

    /**
     * A route that is never matched and has no filters or upstream: it only carries the journal
     * levels that requests refused before routing are recorded at.
     */
    private GatewayRoute createUnroutedRoute(final UnroutedDefinition unrouted)
    {
        if (unrouted == null)
        {
            return null;
        }
        final JournalDefinition journal = unrouted.journal();
        final RouteDefinition definition = new RouteDefinition(UnroutedDefinition.ROUTE_ID, null, null, journal, List.of());
        final ValidationResult validationResult = new ValidationResult();
        final RouteJournalConfig journalConfig = createJournalConfig(journal, validationResult.nested("unrouted").nested("journal"));
        return new DefaultGatewayRoute(List.of(), FalsePredicate.INSTANCE, List.of(), journalConfig, definition);
    }

    /**
     * Builds and validates a route's journal levels. The validation was once defined but never
     * called, which let a FULL request be lowered by an override - a configuration the journal
     * cannot honour, since the request body is written before the status exists.
     */
    private RouteJournalConfig createJournalConfig(final JournalDefinition definition, final ValidationResult result)
    {
        final RouteJournalConfig config = new RouteJournalConfig(
                new JournalDirectionConfig(definition.request().level(), JournalOverrideParser.parseOverrides(definition.request().statusOverrides())),
                new JournalDirectionConfig(definition.response().level(), JournalOverrideParser.parseOverrides(definition.response().statusOverrides()))
        );
        config.validate(result);
        result.throwIfInvalid();
        return config;
    }

    private void instantiateFilters(final FilterCreationContext filterCreationContext, final ValidationResult validationResult, final List<GatewayFilter> instantiatedFilters, final FilterDefinition filterDef)
    {
        final GatewayFilterFactory<ValidatableConfig> typedFactory = filterRegistry.get(filterDef.name());

        try
        {
            final ValidatableConfig c = typedFactory.configClass() != null ? mapper.convertValue(filterDef.args(), typedFactory.configClass()) : new GatewayFilterFactory.EmptyConfig();
            c.validate(validationResult);
            validationResult.throwIfInvalid();
            instantiatedFilters.add(typedFactory.create(c, filterCreationContext));
        }
        catch (JacksonYAMLParseException e)
        {
            throw new ConfigurationException(YamlConfigSupport.formatYamlSyntaxError(null, e));
        }
        catch (UnrecognizedPropertyException e)
        {
            throw new ConfigurationException(YamlConfigSupport.formatUnknownProperty(null, e));
        }
        catch (InvalidFormatException e)
        {
            throw new ConfigurationException(YamlConfigSupport.formatMappingError(null, e));
        }
    }

    public static final class HttpStatusDeserializer extends StdDeserializer<HttpStatus>
    {
        public HttpStatusDeserializer()
        {
            super(HttpStatus.class);
        }

        @Override
        public HttpStatus deserialize(final JsonParser p, final DeserializationContext ctxt)
        {
            final String text = p.getString();

            if (text == null || text.isBlank())
            {
                throw ctxt.weirdStringException(text, HttpStatus.class, "HTTP status code cannot be empty.");
            }

            final int code;
            try
            {
                // Strictly parse the string to prevent silent 0 defaults
                code = Integer.parseInt(text.trim());
            }
            catch (final NumberFormatException e)
            {
                throw ctxt.weirdStringException(text, HttpStatus.class, "HTTP status code must be a valid integer, received: '" + text + "'");
            }

            if (code < 100 || code > 599)
            {
                throw ctxt.weirdNumberException(code, HttpStatus.class, "Invalid HTTP status code: " + code + ". Must be between 100 and 599.");
            }

            return new HttpStatus(code);
        }
    }
}
