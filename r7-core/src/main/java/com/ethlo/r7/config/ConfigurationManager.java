package com.ethlo.r7.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.condition.AndCondition;
import com.ethlo.r7.condition.Condition;
import com.ethlo.r7.condition.ConditionScope;
import com.ethlo.r7.condition.RequestCondition;
import com.ethlo.r7.condition.UnroutedReason;
import com.ethlo.r7.condition.UnroutedReasonCondition;
import com.ethlo.r7.config.model.HttpStatus;
import com.ethlo.r7.filters.RewritePathFactory;
import com.ethlo.r7.journal.JournalPolicy;
import com.ethlo.r7.journal.JournalRule;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.spi.DeclarationLevel;
import com.ethlo.r7.spi.EngineContext;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.FilterRegistry;
import com.ethlo.r7.util.PredicateRegistry;
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
     * The {@link FilterCreationContext#routeId()} root filters are created under: they belong
     * to no single route, and no filter factory currently reads this value, but a real route ID
     * would misleadingly claim ownership by whichever route happened to build first. A group's
     * filters are created under the group's id, for the same reason.
     */
    private static final String ROOT_FILTER_ROUTE_ID = "<root>";

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
        if (type == RoutesDefinition.class)
        {
            return type.cast(loadRoutes(yamlFile, readString(yamlFile), (root, positions) ->
            {
            }));
        }
        return YamlConfigSupport.load(mapper, yamlFile, type);
    }

    private static RoutesDefinition loadRoutes(final Path yamlFile, final String contents, final YamlConfigSupport.TreeInspector inspector)
    {
        return YamlConfigSupport.load(mapper, yamlFile, contents, RoutesDefinition.class, (root, positions) -> RoutesTreeCheck.check(root), inspector);
    }

    /**
     * Loads a routes file as {@link #load(Path, Class)} does, and logs a warning for each value
     * that is valid but almost certainly not what was meant: see {@link RegexLookalikeCheck}.
     */
    public RoutesDefinition loadRoutes(final Path yamlFile)
    {
        return this.loadRoutes(yamlFile, readString(yamlFile));
    }

    private RoutesDefinition loadRoutes(final Path yamlFile, final String contents)
    {
        final RegexLookalikeCheck check = new RegexLookalikeCheck(this.predicateRegistry);
        return loadRoutes(yamlFile, contents, (root, positions) ->
                check.check(root, positions).forEach(warning -> log.warn("{} {}", yamlFile, warning)));
    }

    /**
     * Reads, validates and instantiates a routes file without publishing it: what the gateway
     * does with its routes at startup and on every reload, and all that a config check does.
     * The snapshot carries the file's source digest, of the bytes read and parsed here.
     */
    public RouteRegistry.Snapshot buildFrom(final Path routesFile)
    {
        final byte[] bytes;
        try
        {
            bytes = Files.readAllBytes(routesFile);
        }
        catch (final IOException e)
        {
            throw new UncheckedIOException(e);
        }

        RoutesDefinition routesConfig = this.loadRoutes(routesFile, decode(routesFile, bytes));
        if (routesConfig == null)
        {
            log.warn("No routes found");
            routesConfig = new RoutesDefinition(null, null, null, null, List.of(), null);
        }

        final ValidationResult validationResult = new ValidationResult();
        routesConfig.validate(validationResult);
        if (validationResult.hasErrors())
        {
            throw new ConfigurationException("routes.yaml validation failed. Errors: " + String.join(", ", validationResult.getErrors()));
        }
        return this.build(routesConfig, sourceDigest(bytes));
    }

    /**
     * The SHA-256 of a routes file as read, in hex. Taken before {@code ${VAR}} interpolation, so
     * a secret passed in through the environment never enters it: it identifies the file, and the
     * deployment's own record of its environment covers the rest.
     */
    public static String sourceDigest(final byte[] bytes)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (final NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("Every JVM provides SHA-256", e);
        }
    }

    private static String readString(final Path file)
    {
        try
        {
            return decode(file, Files.readAllBytes(file));
        }
        catch (final IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Strict UTF-8, as {@link Files#readString} is: a malformed file is refused, not read with
     * replacement characters in it.
     */
    private static String decode(final Path file, final byte[] bytes)
    {
        try
        {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        }
        catch (final CharacterCodingException e)
        {
            throw new ConfigurationException("Invalid configuration file: " + file + " is not valid UTF-8");
        }
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
        return this.build(config, null);
    }

    /**
     * @param sourceDigest the routes file's {@link #sourceDigest}, or {@code null} when it was
     *                     not loaded from one
     */
    public RouteRegistry.Snapshot build(final RoutesDefinition config, final String sourceDigest)
    {
        final ValidationResult validationResult = new ValidationResult();

        if (config.routes() == null || config.routes().isEmpty())
        {
            throw new ConfigurationException("No routes defined");
        }

        // 1. Structural Syntax Pass - Validate every individual object
        config.validate(validationResult);
        validationResult.throwIfInvalid();

        // 2. Semantic Analysis Pass - Check for collisions and dangling references, on the
        // routes as inheritance leaves them: a fallback named by a group is every route's in it.
        validateUniqueIds(config.routes());
        validateUniqueRuleIds(config, validationResult);
        checkDeclarationLevels(config, validationResult);
        final List<Placed> placed = place(config, validationResult);
        validationResult.throwIfInvalid();
        validateCrossRouteReferences(placed);

        // 3. Transformation Pass: Only now, when we know the map is sane, do we instantiate.
        // A filter is one instance for everything beneath where it is declared: the root's once,
        // a group's once, and the same instances are then put in front of every route below. A
        // RateLimiter declared there must see every request against one shared set of buckets,
        // not a fresh instance - and its private cache - per route it is wired onto.
        final List<GatewayFilter> rootFilters = new ArrayList<>();
        for (final FilterDefinition filterDef : config.filters())
        {
            instantiateFilters(new FilterCreationContext(ROOT_FILTER_ROUTE_ID, engineContext), validationResult.nested("filters"), rootFilters, filterDef);
        }

        // Journal rules, like filters, are built once where they are declared: a root or group rule
        // is one instance, with one error count, for every route beneath it.
        final CompiledJournal rootJournal = this.compileJournal(config.journal(), "journal", validationResult);
        final Set<String> reported = new HashSet<>();

        final List<GatewayRoute> routes = new ArrayList<>();
        final List<RouteRegistry.RoutingEntry> table = new ArrayList<>();
        int index = 0;
        for (final RouteEntry entry : config.routes())
        {
            switch (entry)
            {
                case RouteDefinition ignored ->
                {
                    final DefaultGatewayRoute route = this.buildRoute(placed.get(index++), rootFilters, List.of(), null, rootJournal, null, reported, validationResult).route();
                    routes.add(route);
                    table.add(new RouteRegistry.Single(route));
                }
                case GroupDefinition group ->
                {
                    final ValidationResult groupResult = validationResult.nested("routes").nested(group.group());
                    final List<GatewayFilter> groupFilters = new ArrayList<>();
                    for (final FilterDefinition filterDef : Optional.ofNullable(group.filters()).orElse(List.of()))
                    {
                        instantiateFilters(new FilterCreationContext(group.group(), engineContext), groupResult.nested("filters"), groupFilters, filterDef);
                    }
                    final CompiledJournal groupJournal = this.compileJournal(group.journal(), "routes." + group.group() + ".journal", validationResult);
                    final Condition groupMatch = group.match() == null
                            ? Condition.TRUE
                            : this.buildMatch(group.match(), groupResult, "routes." + group.group());

                    final List<GatewayRoute> members = new ArrayList<>();
                    final List<Condition> memberMatches = new ArrayList<>();
                    for (int i = 0; i < group.routes().size(); i++)
                    {
                        final BuiltRoute built = this.buildRoute(placed.get(index++), rootFilters, groupFilters, groupMatch, rootJournal, groupJournal, reported, validationResult);
                        members.add(built.route());
                        memberMatches.add(built.ownMatch());
                    }
                    routes.addAll(members);
                    table.add(new RouteRegistry.Group(group.group(), groupMatch, members, memberMatches));
                }
            }
        }

        final GatewayRoute unrouted = this.createUnroutedRoute(config.unrouted(), validationResult);

        // Predicate/filter instantiation (step 3) reports its errors onto the same
        // validationResult rather than throwing, so a poisoned predicate (e.g. an unknown
        // matcher wrapped in 'not:') would otherwise load as whatever fallback the tree built -
        // silently, and possibly inverted to match everything. Fail closed here instead.
        validationResult.throwIfInvalid();

        return new RouteRegistry.Snapshot(config.version(), sourceDigest, routes, table, unrouted);
    }

    /**
     * A route where it is written, with its effective upstream.
     *
     * @param group the group it is declared in, or {@code null}
     * @param path  its validation path: {@code routes.<id>}, or {@code routes.<group>.routes.<id>}
     */
    private record Placed(RouteDefinition route, RouteDefinition declared, GroupDefinition group, String path, DefaultGatewayRoute.Level upstreamLevel)
    {
    }

    /**
     * @param ownMatch the route's own match, without its group's: what the routing table tests
     *                 once the group's has matched
     */
    private record BuiltRoute(DefaultGatewayRoute route, Condition ownMatch)
    {
    }

    /**
     * Every route in the order written, each with the upstream keys it names merged over its
     * group's and then the root's. A route with no {@code targets} after that has no upstream,
     * unless it or its group named upstream keys: those mean nothing without targets, so naming
     * them is refused rather than ignored. Keys named only at the root are for the routes that
     * have targets, and leave one without them - a {@code StaticContent} route - as it is.
     */
    private List<Placed> place(final RoutesDefinition config, final ValidationResult validationResult)
    {
        final List<Placed> placed = new ArrayList<>();
        for (final RouteEntry entry : config.routes())
        {
            switch (entry)
            {
                case RouteDefinition route -> placed.add(placeRoute(route, null, config.upstream(), "routes." + route.id(), validationResult));
                case GroupDefinition group ->
                {
                    final UpstreamConfig groupUpstream = group.upstream() != null ? group.upstream().inherit(config.upstream()) : config.upstream();
                    for (final RouteDefinition route : group.routes())
                    {
                        placed.add(placeRoute(route, group, groupUpstream, "routes." + group.group() + ".routes." + route.id(), validationResult));
                    }
                }
            }
        }
        return placed;
    }

    private static Placed placeRoute(final RouteDefinition route, final GroupDefinition group, final UpstreamConfig outer, final String path, final ValidationResult validationResult)
    {
        final UpstreamConfig merged = route.upstream() != null ? route.upstream().inherit(outer) : outer;
        final boolean namedOnRoute = route.upstream() != null && route.upstream().namesAnyKey();
        final boolean namedOnGroup = group != null && group.upstream() != null && group.upstream().namesAnyKey();
        final boolean namedBelowRoot = namedOnRoute || namedOnGroup;
        UpstreamConfig effective = merged;
        if (merged != null && merged.targets() == null)
        {
            if (namedBelowRoot)
            {
                final String where = group != null ? "here, on group '" + group.group() + "' or at the root" : "here or at the root";
                validationResult.addError(path + ".upstream.targets", "Upstream configuration must contain at least one target configuration: name targets " + where);
            }
            effective = null;
        }
        // Every key comes from the innermost level naming any: a route naming one key takes the
        // rest from outside, but its upstream as a whole is then configured differently from its
        // siblings', so it is its own.
        final DefaultGatewayRoute.Level upstreamLevel = namedOnRoute ? DefaultGatewayRoute.Level.ROUTE
                : namedOnGroup ? DefaultGatewayRoute.Level.GROUP
                : effective != null ? DefaultGatewayRoute.Level.ROOT : DefaultGatewayRoute.Level.ROUTE;
        return new Placed(route.withUpstream(effective), route, group, path, upstreamLevel);
    }

    /**
     * A filter is one instance for everything beneath the level it is declared on, which is wrong
     * for one whose state belongs to a single upstream: its factory names the levels it may be
     * declared on, and the routes refuse to load with it anywhere else.
     */
    private void checkDeclarationLevels(final RoutesDefinition config, final ValidationResult validationResult)
    {
        checkDeclarationLevel(config.filters(), DeclarationLevel.ROOT, validationResult);
        for (final RouteEntry entry : config.routes())
        {
            switch (entry)
            {
                case GroupDefinition group ->
                {
                    final ValidationResult groupResult = validationResult.nested("routes").nested(group.group());
                    checkDeclarationLevel(group.filters(), DeclarationLevel.GROUP, groupResult);
                    for (final RouteDefinition route : group.routes())
                    {
                        checkDeclarationLevel(route.filters(), DeclarationLevel.ROUTE, groupResult.nested("routes").nested(route.id()));
                    }
                }
                case RouteDefinition route -> checkDeclarationLevel(route.filters(), DeclarationLevel.ROUTE, validationResult.nested("routes").nested(route.id()));
            }
        }
    }

    private void checkDeclarationLevel(final List<FilterDefinition> filters, final DeclarationLevel level, final ValidationResult result)
    {
        if (filters == null)
        {
            return;
        }
        for (int i = 0; i < filters.size(); i++)
        {
            final FilterDefinition filter = filters.get(i);
            final GatewayFilterFactory<?> factory = filterRegistry.get(filter.name());
            final Set<DeclarationLevel> levels = factory.levels();
            if (!levels.contains(level))
            {
                final String allowed = levels.stream().sorted().map(DeclarationLevel::label).collect(Collectors.joining(" or "));
                result.addError("filters[" + i + "]", filter.name() + " can be declared on " + allowed + " only, not on " + level.label() + ".");
            }
        }
    }

    private BuiltRoute buildRoute(final Placed placed, final List<GatewayFilter> rootFilters, final List<GatewayFilter> groupFilters, final Condition groupMatch,
                                  final CompiledJournal rootJournal, final CompiledJournal groupJournal, final Set<String> reported, final ValidationResult validationResult)
    {
        final RouteDefinition routeDefinition = placed.route();
        final ValidationResult routeResult = validationResult.nested(placed.path());
        final FilterCreationContext filterCreationContext = new FilterCreationContext(routeDefinition.id(), engineContext);

        final List<GatewayFilter> filters = new ArrayList<>(rootFilters);
        filters.addAll(groupFilters);
        if (routeDefinition.filters() != null)
        {
            for (final FilterDefinition filterDef : routeDefinition.filters())
            {
                instantiateFilters(filterCreationContext, routeResult.nested("filters"), filters, filterDef);
            }
        }

        checkQueryRewrites(filters, routeResult);

        final CompiledJournal routeJournal = this.compileJournal(routeDefinition.journal(), placed.path() + ".journal", validationResult);
        final JournalPolicy journalConfig = policy(routeJournal, groupJournal, rootJournal, reported, validationResult);

        // An empty match is a fallback-only route (docs/config.md): it must stay
        // false, as build() would turn an empty tree into an always-true condition.
        Condition match = Condition.FALSE;
        if (routeDefinition.match() != null && !routeDefinition.match().isFallbackOnly())
        {
            match = this.buildMatch(routeDefinition.match(), routeResult, placed.path());
        }

        final List<String> urls = routeDefinition.upstream() != null
                ? routeDefinition.upstream().targets().stream().map(TargetConfig::url).toList()
                : List.of();
        final Condition effective = groupMatch != null ? new AndCondition(List.of(groupMatch, match)) : match;
        final DefaultGatewayRoute route = new DefaultGatewayRoute(urls, effective, filters, rootFilters.size(), groupFilters.size(),
                placed.group() != null ? placed.group().group() : null, placed.upstreamLevel(), journalConfig, routeDefinition);
        return new BuiltRoute(route, match);
    }

    /**
     * Every RewritePath tests the path the client sent, so with two on a route the last match
     * wins. A rewrite that sets the query would leave its parameters behind for a later one to
     * inherit, so it must be the only RewritePath the route runs, root and group ones included.
     */
    private static void checkQueryRewrites(final List<GatewayFilter> filters, final ValidationResult routeResult)
    {
        final List<RewritePathFactory.GF> rewrites = filters.stream()
                .filter(RewritePathFactory.GF.class::isInstance)
                .map(RewritePathFactory.GF.class::cast)
                .toList();
        if (rewrites.size() > 1 && rewrites.stream().anyMatch(RewritePathFactory.GF::setsQuery))
        {
            routeResult.addError("filters", "A RewritePath that sets the query (a ? in its replacement) must be the only RewritePath on the route, counting the root's and the group's: give each rewrite a route of its own.");
        }
    }

    private Condition buildMatch(final ConditionDefinition match, final ValidationResult result, final String path)
    {
        // An empty match matches nothing: for a route, one reached only as a fallback; for a
        // group, one whose routes are.
        if (match.isFallbackOnly())
        {
            return Condition.FALSE;
        }
        match.validateTree(result);
        try
        {
            return match.build(predicateRegistry, ConditionScope.ROUTE_MATCH, result);
        }
        catch (final ConfigurationException e)
        {
            throw new ConfigurationException(String.format("[%s.match] %s", path, e.getMessage()));
        }
    }

    /**
     * Group ids and route ids are one namespace: {@code gateway.route.group} and
     * {@code fallback.route_id} must never be ambiguous.
     */
    private void validateUniqueIds(final List<RouteEntry> entries)
    {
        final Set<String> seen = new HashSet<>();
        for (final RouteEntry entry : entries)
        {
            final List<String> ids = new ArrayList<>();
            ids.add(entry.entryId());
            if (entry instanceof GroupDefinition group)
            {
                group.routes().forEach(r -> ids.add(r.id()));
            }
            for (final String id : ids)
            {
                if (!seen.add(id))
                {
                    throw new ConfigurationException(String.format("[routes.%s] id is not unique: group and route ids share one namespace", id));
                }
            }
        }
    }

    private void validateCrossRouteReferences(final List<Placed> placed)
    {
        final Set<String> routeIds = new HashSet<>();
        placed.forEach(p -> routeIds.add(p.route().id()));
        final Set<String> groupIds = new HashSet<>();
        placed.stream().filter(p -> p.group() != null).forEach(p -> groupIds.add(p.group().group()));

        for (final Placed p : placed)
        {
            final RouteDefinition r = p.route();
            final String fallbackRouteId = fallbackOf(r);
            if (fallbackRouteId == null)
            {
                continue;
            }
            final boolean ownFallback = p.declared().upstream() != null && p.declared().upstream().fallback() != null;
            // Prevent infinite loops
            if (fallbackRouteId.equals(r.id()))
            {
                if (ownFallback)
                {
                    throw new ConfigurationException("Configuration error: Route '" + r.id() + "' specifies itself as its fallback.route_id.");
                }
                final String from = p.group() != null && p.group().upstream() != null && p.group().upstream().fallback() != null
                        ? "group '" + p.group().group() + "'"
                        : "the root";
                throw new ConfigurationException("Configuration error: Route '" + r.id() + "' inherits fallback.route_id '" + fallbackRouteId + "' from " + from
                        + ", which names the route itself. Declare the fallback route outside the group, or the fallback on the routes that use it.");
            }

            if (groupIds.contains(fallbackRouteId))
            {
                throw new ConfigurationException("Configuration error: Route '" + r.id() + "' references a fallback.route_id '" + fallbackRouteId + "' that is a group; a fallback names a route.");
            }

            // Ensure the fallback route actually exists in the registry
            if (!routeIds.contains(fallbackRouteId))
            {
                throw new ConfigurationException("Configuration error: Route '" + r.id() + "' references a fallback.route_id '" + fallbackRouteId + "' that does not exist.");
            }
        }
        validateAcyclicFallbacks(placed.stream().map(Placed::route).toList());
    }

    private static String fallbackOf(final RouteDefinition route)
    {
        if (route.upstream() == null || route.upstream().fallback() == null)
        {
            return null;
        }
        final String id = route.upstream().fallback().routeId();
        return id == null || id.isBlank() ? null : id;
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
            final String target = fallbackOf(r);
            if (target != null)
            {
                fallbackOf.put(r.id(), target);
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
     * levels that requests refused before routing are recorded at. It does not inherit the
     * root's journal: refusals are mostly scanner traffic, and levels chosen for real routes
     * would journal it by accident. Its {@code on_request} rules are checked on every refusal.
     */
    private GatewayRoute createUnroutedRoute(final UnroutedDefinition unrouted, final ValidationResult validationResult)
    {
        if (unrouted == null || unrouted.journal() == null)
        {
            return null;
        }
        final JournalDefinition journal = unrouted.journal();
        final RouteDefinition definition = new RouteDefinition(UnroutedDefinition.ROUTE_ID, null, null, journal, List.of());
        final JournalPolicy policy = new JournalPolicy(levelOf(journal.request()), levelOf(journal.response()),
                this.compileRules(journal.onRequest(), "unrouted.journal.on_request", ConditionScope.UNROUTED, validationResult),
                List.of());
        return new DefaultGatewayRoute(List.of(), Condition.FALSE, List.of(), policy, definition);
    }

    private static JournalLevel levelOf(final JournalDirectionDefinition direction)
    {
        return direction != null && direction.level() != null ? direction.level() : JournalLevel.NONE;
    }

    /**
     * A journal block as declared at one level, its rules built.
     *
     * @param path where it is declared: {@code journal}, {@code routes.<group>.journal}, ...
     */
    private record CompiledJournal(JournalDefinition definition, String path, List<JournalRule> onRequest, List<JournalRule> onResponse)
    {
    }

    private CompiledJournal compileJournal(final JournalDefinition definition, final String path, final ValidationResult validationResult)
    {
        if (definition == null)
        {
            return null;
        }
        return new CompiledJournal(definition, path,
                this.compileRules(definition.onRequest(), path + ".on_request", ConditionScope.ON_REQUEST, validationResult),
                this.compileRules(definition.onResponse(), path + ".on_response", ConditionScope.ON_RESPONSE, validationResult));
    }

    private List<JournalRule> compileRules(final List<JournalRuleDefinition> rules, final String listPath, final ConditionScope scope, final ValidationResult validationResult)
    {
        final List<JournalRule> compiled = new ArrayList<>(rules.size());
        for (int i = 0; i < rules.size(); i++)
        {
            final JournalRuleDefinition rule = rules.get(i);
            final String path = listPath + "[" + i + "]";
            final Condition condition;
            try
            {
                condition = rule.match().build(predicateRegistry, scope, validationResult.nested(path));
            }
            catch (final ConfigurationException e)
            {
                throw new ConfigurationException(String.format("[%s.match] %s", path, e.getMessage()));
            }
            compiled.add(new JournalRule(rule.id() != null ? rule.id() : path, path, scope == ConditionScope.UNROUTED ? gated(condition) : condition,
                    rule.request() != null ? rule.request().level() : null,
                    rule.response() != null ? rule.response().level() : null,
                    truncateAt(rule.request()), truncateAt(rule.response())));
        }
        return compiled;
    }

    /**
     * An {@code unrouted} rule that reads the request anywhere in its tree is checked only on a
     * route miss: on every other refusal r7 never interpreted the request, so the rule is skipped
     * as if it did not match. The whole rule is gated, not each leaf, since a leaf that is merely
     * false would let {@code not} turn it true. A rule of only {@code UnroutedReason} and
     * {@code Sample} is checked on every refusal.
     */
    private static Condition gated(final Condition condition)
    {
        return readsRequest(condition)
                ? new AndCondition(List.of(new UnroutedReasonCondition(EnumSet.of(UnroutedReason.NO_ROUTE)), condition))
                : condition;
    }

    private static boolean readsRequest(final Condition condition)
    {
        if (condition instanceof RequestCondition)
        {
            return true;
        }
        for (final Condition child : condition.children())
        {
            if (readsRequest(child))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * A route's journal policy: each base key ({@code level}, {@code truncate_body_at}) from the
     * innermost level naming it, else its default; the rules of every level, the route's checked first, then its group's, then
     * the root's.
     * <p>
     * Refuses an {@code on_response} rule naming the request while the request can be
     * {@code FULL}: its body streams into the journal before any response exists and cannot be
     * un-written. Checked per route, since the rule that can make it {@code FULL} may sit at
     * another level, and reported once per offending rule however many routes share it.
     */
    private static JournalPolicy policy(final CompiledJournal route, final CompiledJournal group, final CompiledJournal root, final Set<String> reported, final ValidationResult validationResult)
    {
        JournalLevel request = null;
        String requestFrom = null;
        JournalLevel response = null;
        long requestTruncateAt = JournalPolicy.NO_CAP;
        long responseTruncateAt = JournalPolicy.NO_CAP;
        final List<JournalRule> onRequest = new ArrayList<>();
        final List<JournalRule> onResponse = new ArrayList<>();
        for (final CompiledJournal level : new CompiledJournal[]{route, group, root})
        {
            if (level == null)
            {
                continue;
            }
            final JournalDefinition definition = level.definition();
            if (request == null && definition.request() != null && definition.request().level() != null)
            {
                request = definition.request().level();
                requestFrom = level.path() + ".request";
            }
            if (response == null && definition.response() != null && definition.response().level() != null)
            {
                response = definition.response().level();
            }
            if (requestTruncateAt == JournalPolicy.NO_CAP)
            {
                requestTruncateAt = truncateAt(definition.request());
            }
            if (responseTruncateAt == JournalPolicy.NO_CAP)
            {
                responseTruncateAt = truncateAt(definition.response());
            }
            onRequest.addAll(level.onRequest());
            onResponse.addAll(level.onResponse());
        }

        String fullFrom = request == JournalLevel.FULL ? "the base level " + requestFrom + " is FULL" : null;
        for (final JournalRule rule : onRequest)
        {
            if (fullFrom == null && rule.request() == JournalLevel.FULL)
            {
                fullFrom = rule.path() + " can make it FULL";
            }
        }
        if (fullFrom != null)
        {
            for (final JournalRule rule : onResponse)
            {
                if (rule.request() != null && rule.request() != JournalLevel.FULL && reported.add(rule.path()))
                {
                    validationResult.nested(rule.path()).addError("request", "cannot change the request level: " + fullFrom
                            + ", and a request body that was journaled cannot be un-written.");
                }
            }
        }

        return new JournalPolicy(request != null ? request : JournalLevel.NONE, response != null ? response : JournalLevel.NONE,
                requestTruncateAt, responseTruncateAt, onRequest, onResponse);
    }

    /**
     * The body cap a direction names, each key inherited on its own like {@code level}.
     */
    private static long truncateAt(final JournalDirectionDefinition direction)
    {
        return direction != null && direction.truncateBodyAt() != null ? direction.truncateBodyAt().bytes() : JournalPolicy.NO_CAP;
    }

    /**
     * A rule's id is journaled in place of its path, so it must name one rule in the file.
     */
    private static void validateUniqueRuleIds(final RoutesDefinition config, final ValidationResult validationResult)
    {
        final Map<String, String> seen = new HashMap<>();
        collectRuleIds(config.journal(), "journal", seen, validationResult);
        if (config.unrouted() != null)
        {
            collectRuleIds(config.unrouted().journal(), "unrouted.journal", seen, validationResult);
        }
        for (final RouteEntry entry : config.routes())
        {
            switch (entry)
            {
                case RouteDefinition route -> collectRuleIds(route.journal(), "routes." + route.id() + ".journal", seen, validationResult);
                case GroupDefinition group ->
                {
                    collectRuleIds(group.journal(), "routes." + group.group() + ".journal", seen, validationResult);
                    for (final RouteDefinition route : group.routes())
                    {
                        collectRuleIds(route.journal(), "routes." + group.group() + ".routes." + route.id() + ".journal", seen, validationResult);
                    }
                }
            }
        }
    }

    private static void collectRuleIds(final JournalDefinition journal, final String path, final Map<String, String> seen, final ValidationResult validationResult)
    {
        if (journal == null)
        {
            return;
        }
        collectRuleIds(journal.onRequest(), path + ".on_request", seen, validationResult);
        collectRuleIds(journal.onResponse(), path + ".on_response", seen, validationResult);
    }

    private static void collectRuleIds(final List<JournalRuleDefinition> rules, final String listPath, final Map<String, String> seen, final ValidationResult validationResult)
    {
        for (int i = 0; i < rules.size(); i++)
        {
            final String id = rules.get(i).id();
            if (id == null)
            {
                continue;
            }
            final String path = listPath + "[" + i + "]";
            final String previous = seen.putIfAbsent(id, path);
            if (previous != null)
            {
                validationResult.nested(path).addError("id", "'" + id + "' is already the id of " + previous);
            }
        }
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
