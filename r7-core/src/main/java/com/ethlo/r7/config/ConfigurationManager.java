package com.ethlo.r7.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.predicates.PredicateRegistry;
import com.ethlo.r7.spi.EngineContext;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.FilterRegistry;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.dataformat.yaml.JacksonYAMLParseException;
import tools.jackson.dataformat.yaml.YAMLMapper;

public final class ConfigurationManager
{
    private static final ObjectMapper mapper;

    static
    {
        final SimpleModule r7Module = new SimpleModule();
        r7Module.addDeserializer(Duration.class, new HumanDurationDeserializer());
        r7Module.addDeserializer(DataSize.class, new HumanDataSizeDeserializer());

        mapper = YAMLMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .addModule(r7Module) // Inject the custom parsers here
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

    public static ValidationResult validate(ValidatableConfig config)
    {
        final ValidationResult validationResult = new ValidationResult();
        config.validate(validationResult.nested("routes"));
        return validationResult;
    }

    public static <T> T load(Path yamlFile, Class<T> type)
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

        try
        {
            return mapper.readValue(interpolated, type);
        }
        catch (JacksonYAMLParseException e)
        {
            throw new ConfigurationException(formatYamlSyntaxError(yamlFile, e));
        }
        catch (UnrecognizedPropertyException e)
        {
            throw new ConfigurationException(formatUnknownProperty(yamlFile, e));
        }
        catch (MismatchedInputException e)
        {
            throw new ConfigurationException(formatMappingError(yamlFile, e));
        }
    }

    private static String formatYamlSyntaxError(
            final Path file,
            final JacksonYAMLParseException e)
    {
        final TokenStreamLocation loc = e.getLocation();

        // Extract the highly readable SnakeYAML explanation, cutting off Jackson's internal trace info
        String cleanMessage = e.getMessage();
        final int sourceTraceIndex = cleanMessage.indexOf("\n at [Source");
        if (sourceTraceIndex != -1)
        {
            cleanMessage = cleanMessage.substring(0, sourceTraceIndex);
        }

        final StringBuilder sb = new StringBuilder();

        sb.append("Invalid YAML syntax in configuration file: ")
                .append(file)
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        sb.append(cleanMessage)
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        if (loc != null)
        {
            sb.append("Fix the structural error around line ")
                    .append(loc.getLineNr())
                    .append(", column ")
                    .append(loc.getColumnNr())
                    .append(".");
        }

        return sb.toString();
    }

    private static String formatMappingError(
            final Path file,
            final MismatchedInputException e)
    {
        final TokenStreamLocation loc = e.getLocation();

        final StringBuilder sb = new StringBuilder();

        sb.append("Configuration mapping error in ")
                .append(file);

        if (loc != null)
        {
            sb.append(" at line ")
                    .append(loc.getLineNr())
                    .append(", column ")
                    .append(loc.getColumnNr());
        }

        sb.append(System.lineSeparator());

        if (!e.getPath().isEmpty())
        {
            sb.append("Property path: ");

            for (JacksonException.Reference ref : e.getPath())
            {
                sb.append(ref.getPropertyName()).append(".");
            }

            sb.setLength(sb.length() - 1);

            sb.append(System.lineSeparator());
        }

        sb.append(e.getOriginalMessage());

        return sb.toString();
    }

    private static String formatUnknownProperty(
            final Path file,
            final UnrecognizedPropertyException e)
    {
        final TokenStreamLocation loc = e.getLocation();
        final StringBuilder sb = new StringBuilder();

        sb.append("Invalid configuration file: ")
                .append(file)
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        sb.append("Unknown configuration option: '")
                .append(e.getPropertyName())
                .append("'");

        if (!e.getPath().isEmpty())
        {
            sb.append(" at [");
            final StringBuilder pathBuilder = new StringBuilder();

            for (final JacksonException.Reference ref : e.getPath())
            {
                if (ref.getPropertyName() != null)
                {
                    if (!pathBuilder.isEmpty() && pathBuilder.charAt(pathBuilder.length() - 1) != ']')
                    {
                        pathBuilder.append(".");
                    }
                    pathBuilder.append(ref.getPropertyName());
                }
                else if (ref.getIndex() >= 0)
                {
                    pathBuilder.append("[").append(ref.getIndex()).append("]");
                }
            }
            sb.append(pathBuilder).append("]");
        }

        sb.append(System.lineSeparator());

        if (loc != null)
        {
            sb.append("Location: line ")
                    .append(loc.getLineNr())
                    .append(", column ")
                    .append(loc.getColumnNr())
                    .append(System.lineSeparator());
        }

        sb.append(System.lineSeparator());

        if (e.getKnownPropertyIds() != null && !e.getKnownPropertyIds().isEmpty())
        {
            sb.append("Known properties are: ")
                    .append(e.getKnownPropertyIds())
                    .append(System.lineSeparator())
                    .append(System.lineSeparator());
        }

        sb.append("Remove the option or check for spelling mistakes.");

        return sb.toString();
    }

    public void load(RoutesDefinition config, RouteRegistry routeRegistry)
    {
        final ValidationResult validationResult = validate(config);
        validationResult.throwIfInvalid();

        final List<GatewayRoute> routes = config.routes().stream()
                .map(routeDefinition ->
                {
                    final FilterCreationContext filterCreationContext = new FilterCreationContext(routeDefinition.id().toString(), engineContext);

                    // Load global filters
                    final List<GatewayFilter> globalFilters = new ArrayList<>();
                    for (final FilterDefinition filterDef : config.globalFilters())
                    {
                        instantiateFilters(filterCreationContext, validationResult, globalFilters, filterDef);
                    }

                    final List<GatewayFilter> filters = new ArrayList<>(globalFilters);
                    if (routeDefinition.filters() != null)
                    {
                        for (final FilterDefinition filterDef : routeDefinition.filters())
                        {
                            instantiateFilters(filterCreationContext, validationResult, filters, filterDef);
                        }
                    }

                    final RouteJournalConfig journalConfig = createJournalConfig(routeDefinition.journal());

                    // Validate the structure and the plugin names
                    if (routeDefinition.match() != null)
                    {
                        routeDefinition.match().validateTree(validationResult, predicateRegistry);
                    }

                    final GatewayPredicate predicate;
                    try
                    {
                        predicate = routeDefinition.match().build(predicateRegistry);
                    }
                    catch (final ConfigurationException e)
                    {
                        // Grab the route ID for the breadcrumb, fallback to 'unknown' if not set yet
                        final String routeId = routeDefinition.id().toString();
                        throw new ConfigurationException(String.format("[routes.%s.match] %s", routeId, e.getMessage()));
                    }

                    if (routeDefinition.upstream().targets() == null)
                    {
                        new ValidatorUtils(validationResult).invalid("targets", null, "upstream targets required");
                        validationResult.throwIfInvalid();
                    }

                    final List<CharSequence> urls = routeDefinition.upstream().targets().stream().map(TargetConfig::url).map(CharSequence.class::cast).toList();
                    return (GatewayRoute) new DefaultGatewayRoute(urls, predicate, filters, journalConfig, routeDefinition);
                })
                .toList();

        validateCrossRouteReferences(routes);
        routeRegistry.updateRoutes(config.version(), routes);
    }

    public void validateCrossRouteReferences(final List<GatewayRoute> routes)
    {
        for (final GatewayRoute r : routes)
        {
            final DefaultGatewayRoute route = (DefaultGatewayRoute) r;
            if (route.routeDefinition() != null && route.routeDefinition().upstream() != null && route.routeDefinition().upstream().fallback() != null)
            {
                final FallbackConfig fallbackRoute = route.routeDefinition().upstream().fallback();
                final String fallbackRouteId = fallbackRoute.routeId();
                if (fallbackRouteId != null && !fallbackRouteId.isBlank())
                {
                    // Prevent infinite loops
                    if (fallbackRouteId.equals(route.id().toString()))
                    {
                        throw new ConfigurationException("Configuration error: Route '" + route.id() + "' specifies itself as its fallback.route_id.");
                    }

                    // Ensure the fallback route actually exists in the registry
                    if (routes.stream().noneMatch(e -> e.id().toString().equals(fallbackRouteId)))
                    {
                        throw new ConfigurationException("Configuration error: Route '" + route.id() + "' references a fallback.route_id '" + fallbackRouteId + "' that does not exist.");
                    }
                }
            }
        }
    }

    private RouteJournalConfig createJournalConfig(JournalDefinition definition)
    {
        return new RouteJournalConfig(
                new JournalDirectionConfig(definition.request().level(), JournalOverrideParser.parseOverrides(definition.request().statusOverrides())),
                new JournalDirectionConfig(definition.response().level(), JournalOverrideParser.parseOverrides(definition.response().statusOverrides()))
        );
    }

    private void instantiateFilters(final FilterCreationContext filterCreationContext, final ValidationResult validationResult, final List<GatewayFilter> instantiatedFilters, final FilterDefinition filterDef)
    {
        final GatewayFilterFactory<ValidatableConfig> typedFactory = filterRegistry.get(filterDef.name());
        final ValidatableConfig c = typedFactory.configClass() != null ? mapper.convertValue(filterDef.args(), typedFactory.configClass()) : new GatewayFilterFactory.EmptyConfig();
        c.validate(validationResult);
        validationResult.throwIfInvalid();
        instantiatedFilters.add(typedFactory.create(c, filterCreationContext));
    }

    public static final class HumanDurationDeserializer extends StdDeserializer<Duration>
    {
        private static final Pattern PATTERN = Pattern.compile("^(\\d+)(ms|s|m|h|d)?$");

        public HumanDurationDeserializer()
        {
            super(Duration.class);
        }

        @Override
        public Duration deserialize(final JsonParser p, final DeserializationContext ctxt)
        {
            final String text = p.getString().trim().toLowerCase();
            final Matcher matcher = PATTERN.matcher(text);

            if (!matcher.matches())
            {
                throw new ConfigurationException("Invalid duration format: '" + text + "'. Supported formats: 10ms, 5s, 2m, 1h");
            }

            final long amount = Long.parseLong(matcher.group(1));
            final String unit = matcher.group(2);

            return switch (unit)
            {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                default -> throw new ConfigurationException("Unknown duration unit: " + unit);
            };

        }
    }

    public static final class HumanDataSizeDeserializer extends StdDeserializer<DataSize>
    {
        // Adjust the regex if you want to support spaces (e.g., "200 MB")
        private static final Pattern PATTERN = Pattern.compile("^(\\d+)(b|kb|mb|gb)?$", Pattern.CASE_INSENSITIVE);

        public HumanDataSizeDeserializer()
        {
            super(DataSize.class);
        }

        @Override
        public DataSize deserialize(final JsonParser p, final DeserializationContext ctxt)
        {
            final String text = p.getString().trim();
            final Matcher matcher = PATTERN.matcher(text);
            if (!matcher.matches())
            {
                throw new ConfigurationException("Invalid data size format: '" + text + "'. Supported formats: 1024, 8kb, 200mb, 1gb");
            }

            final long amount = Long.parseLong(matcher.group(1));
            final String unit = matcher.group(2) != null ? matcher.group(2).toLowerCase() : "b";

            return switch (unit)
            {
                case "b" -> DataSize.ofBytes(amount);
                case "kb" -> DataSize.ofKilobytes(amount);
                case "mb" -> DataSize.ofMegabytes(amount);
                case "gb" -> DataSize.ofGigabytes(amount);
                default -> throw new ConfigurationException("Unknown data size unit: " + unit);
            };
        }
    }
}