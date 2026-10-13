package com.ethlo.r7.schema;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import com.ethlo.r7.condition.Leg;
import com.ethlo.r7.condition.SampleConfig;
import com.ethlo.r7.config.ConfigurationManager;
import com.ethlo.r7.config.UpstreamConfig;
import com.ethlo.r7.config.model.HttpStatus;
import com.ethlo.r7.doc.DefaultValue;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.doc.FormatPattern;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.spi.GatewayResponsePredicateFactory;
import com.ethlo.r7.util.PredicateRegistry;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

public final class JsonSchemaGenerator
{
    public static void main(final String[] args) throws IOException
    {
        final JsonSchemaGenerator generator = new JsonSchemaGenerator();
        final RootSchema schemaTree = generator.generateGatewaySchema();

        final SimpleModule r7Module = new SimpleModule();
        r7Module.addDeserializer(HttpStatus.class, new ConfigurationManager.HttpStatusDeserializer());
        r7Module.addSerializer(HttpStatus.class, new HttpStatusSerializer());

        final YAMLFactory yamlFactory = YAMLFactory.builder()
                .disable(YAMLWriteFeature.SPLIT_LINES)
                .build();

        final YAMLMapper yamlMapper = YAMLMapper.builder(yamlFactory)
                .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
                .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
                .addModule(r7Module)
                .build();

        final String yaml = yamlMapper.writeValueAsString(schemaTree);
        Files.writeString(Path.of("docs/schemas/latest.yaml"), yaml);
    }

    private static final List<String> ALL_LEVELS = List.of("NONE", "HEADERS", "METADATA", "FULL");

    public RootSchema generateGatewaySchema()
    {
        final Map<String, SchemaNode> defs = new TreeMap<>();
        defs.put("filter", buildFilterDefinition());
        defs.put("predicate", buildPredicateDefinition());
        defs.put("route", buildRouteDefinition());
        defs.put("group", buildGroupDefinition());

        final Map<String, SchemaNode> properties = new TreeMap<>();

        properties.put("version", new PrimitiveSchema(
                "string", null, null, null, null,
                "A version label for this file, logged with the file's source digest on every load and shown by the management API.",
                null, null, null
        ));
        properties.put("filters", new ArraySchema(
                        "array",
                        new RefSchema("#/$defs/filter", null, null),
                        "Filters for every route: one instance each for the whole gateway. They run before a group's and a route's on the request, and after them on the response.",
                        null
                )
        );
        properties.put("upstream", buildUpstreamSchema("Upstream keys every route inherits, each unless its group or the route names it."));
        properties.put("journal", buildJournalSchema(ALL_LEVELS, true, "Journal levels every route inherits, each unless its group or the route names it, and rules checked after theirs. A route nothing names is journaled at NONE."));

        final List<String> belowFull = List.of("NONE", "HEADERS", "METADATA");
        properties.put("unrouted", new ObjectSchema(
                "object",
                new TreeMap<>(Map.of("journal", buildJournalSchema(belowFull, false,
                        "Journal levels for requests refused before any route is chosen. FULL is not allowed."))),
                List.of("journal"),
                new BoolProps(false),
                null, null, null, null,
                "Journaling for requests refused before routing (no route, ambiguous path, bad Transfer-Encoding, TRACE, regex budget), recorded under route ID <unrouted>.",
                null,
                null
        ));

        properties.put("routes", new ArraySchema(
                        "array",
                        new OneOfSchema(List.of(new RefSchema("#/$defs/route", null, null), new RefSchema("#/$defs/group", null, null)), null, null),
                        "The routing table, tried in order: routes (id) and groups of routes (group). The first route that matches takes the request.",
                        null
                )
        );

        return new RootSchema(
                "https://json-schema.org/draft/2020-12/schema",
                "object",
                "r7 Gateway Configuration",
                defs,
                properties
        );
    }

    private Object parseDefaultValue(final Class<?> type, final String defaultValue)
    {
        if (defaultValue == null)
        {
            return null;
        }

        if (type == boolean.class || type == Boolean.class)
        {
            return Boolean.valueOf(defaultValue);
        }

        if (type == int.class || type == Integer.class || type == long.class || type == Long.class)
        {
            try
            {
                return Integer.valueOf(defaultValue.trim());
            }
            catch (final NumberFormatException e)
            {
                return defaultValue;
            }
        }

        if (type.isRecord())
        {
            if (defaultValue.isBlank() || "{}".equals(defaultValue))
            {
                return Map.of();
            }
        }

        return defaultValue;
    }

    private SchemaNode buildRouteDefinition()
    {
        final Map<String, SchemaNode> properties = new TreeMap<>();

        properties.put("id", new PrimitiveSchema(
                        "string", null, null, null, null,
                        "A unique identifier for this route. Used for logging, metrics, and fallback references.",
                        null, null, null
                )
        );

        properties.put("match", new ArraySchema(
                        "array",
                        new RefSchema("#/$defs/predicate", null, null),
                        "Conditions that must be met for this route to handle a request. Required; an empty list (match: []) matches nothing, for a route reached only as a fallback.",
                        null
                )
        );

        properties.put("filters", new ArraySchema(
                        "array",
                        new RefSchema("#/$defs/filter", null, null),
                        "A list of processing steps applied to the request before forwarding, and to the response before returning.",
                        null
                )
        );

        properties.put("upstream", buildUpstreamSchema("Where this route forwards requests. Each key not named here is inherited from the route's group, then the root."));
        properties.put("journal", buildJournalSchema(ALL_LEVELS, true, "The route's journal: each key not named here is inherited from its group, then the root, and its rules are checked before theirs."));

        // Neither upstream nor filters is required here: a route may inherit its upstream from
        // its group or the root, which a schema of the route alone cannot see.
        return new ObjectSchema(
                "object",
                properties,
                List.of("id", "match"),
                new BoolProps(false),
                null, null,
                null,
                null,
                "Defines a single traffic route and its associated behaviors.",
                null,
                null
        );
    }

    private SchemaNode buildGroupDefinition()
    {
        final Map<String, SchemaNode> properties = new TreeMap<>();
        properties.put("group", new PrimitiveSchema(
                "string", null, null, null, null,
                "A unique identifier for this group, in one namespace with route ids. Journaled as gateway.route.group for each of its routes.",
                null, null, null
        ));
        properties.put("match", new ArraySchema(
                "array",
                new RefSchema("#/$defs/predicate", null, null),
                "Conditions ANDed before each route's own. Optional; a request this does not match skips the group's routes and goes on to the next entry.",
                null
        ));
        properties.put("filters", new ArraySchema(
                "array",
                new RefSchema("#/$defs/filter", null, null),
                "Filters for every route of the group: one instance each for the group. They run after the root's and before a route's own on the request.",
                null
        ));
        properties.put("upstream", buildUpstreamSchema("Upstream keys the group's routes inherit, each unless the route names it; keys not named here are inherited from the root."));
        properties.put("journal", buildJournalSchema(ALL_LEVELS, true, "Journal levels the group's routes inherit, each unless the route names it, and rules checked after a route's own and before the root's."));
        properties.put("routes", new ArraySchema(
                "array",
                new RefSchema("#/$defs/route", null, null),
                "The group's routes, tried in order. Groups nest one level: a group holds routes, not groups.",
                null,
                1
        ));
        return new ObjectSchema(
                "object",
                properties,
                List.of("group", "routes"),
                new BoolProps(false),
                null, null,
                null,
                null,
                "Routes sharing a match, filters and upstream.",
                null,
                null
        );
    }

    private SchemaNode buildUpstreamSchema(final String description)
    {
        final SchemaNode schema = buildInnerConfigSchema(UpstreamConfig.class);
        if (schema instanceof ObjectSchema o)
        {
            return new ObjectSchema(o.type(), o.properties(), o.required(), o.additionalProperties(), o.minProperties(), o.maxProperties(), o.anyOf(), o.oneOf(), description, o.defaultValue(), o.defaultSnippets());
        }
        return schema;
    }

    /**
     * @param levels    the levels allowed, as a base level and in a rule
     * @param withRules whether {@code on_request} and {@code on_response} are accepted
     */
    private SchemaNode buildJournalSchema(final List<String> levels, final boolean withRules, final String description)
    {
        final SchemaNode levelSchema = new PrimitiveSchema(
                "string",
                levels,
                null, null, null,
                "The journaling verbosity level.",
                null, null, null
        );

        final SchemaNode levelDef = new ObjectSchema(
                "object",
                new TreeMap<>(Map.of("level", levelSchema)),
                null,
                new BoolProps(false),
                1, null, null, null,
                "Journal settings for this direction of the exchange.",
                null,
                null
        );

        final Map<String, SchemaNode> properties = new TreeMap<>(Map.of("request", levelDef, "response", levelDef));
        if (withRules)
        {
            properties.put("on_request", buildJournalRules(levelDef,
                    "Rules checked once the route is chosen, before its filters run: the client request as sent. Per direction, the first matching rule naming it wins; a route's rules are checked before its group's, then the root's."));
            properties.put("on_response", buildJournalRules(levelDef,
                    "Rules checked once the response head is final, before it is written; they override what on_request resolved. A request level that is or can become FULL cannot be changed here."));
        }

        return new ObjectSchema(
                "object",
                properties,
                null,
                new BoolProps(false),
                null, null, null, null,
                description,
                null,
                null
        );
    }

    private static SchemaNode buildJournalRules(final SchemaNode levelDef, final String description)
    {
        final Map<String, SchemaNode> properties = new TreeMap<>();
        properties.put("id", new PrimitiveSchema("string", null, null, null, null,
                "A label, unique among all rules in the file, journaled in gateway.journal.rule in place of the rule's path when it fires.",
                null, null, null));
        properties.put("match", new ArraySchema("array", new RefSchema("#/$defs/predicate", null, null),
                "Conditions that must all hold for the rule to fire.", null, 1));
        properties.put("request", levelDef);
        properties.put("response", levelDef);
        final SchemaNode rule = new ObjectSchema(
                "object",
                properties,
                List.of("match"),
                new BoolProps(false),
                null, null,
                List.of(new ObjectSchema(null, null, List.of("request"), null, null, null, null, null, null, null, null),
                        new ObjectSchema(null, null, List.of("response"), null, null, null, null, null, null, null, null)),
                null,
                "When match holds, the directions named here replace the levels resolved so far.",
                null,
                null
        );
        return new ArraySchema("array", rule, description, null);
    }

    private SchemaNode buildFilterDefinition()
    {
        return buildSpiDefinition(
                spiEntries(GatewayFilterFactory.class, GatewayFilterFactory::name, GatewayFilterFactory::configClass),
                Map.of(),
                Map.of()
        );
    }

    private SchemaNode buildPredicateDefinition()
    {
        final Map<String, SchemaNode> logicalOperators = new TreeMap<>();

        logicalOperators.put("not", new RefSchema("#/$defs/predicate", null, null));

        // minItems mirrors the load-time refusal of 'and: []' and 'or: []', so the editor flags them too.
        final SchemaNode arrayRef = new ArraySchema("array", new RefSchema("#/$defs/predicate", null, null), null, null, 1);
        logicalOperators.put("or", arrayRef);
        logicalOperators.put("and", arrayRef);

        // Sample is a node of the condition tree, not a predicate: no factory, and no leg.
        final Description sampleDescription = SampleConfig.class.getAnnotation(Description.class);
        final ObjectSchema sample = (ObjectSchema) buildInnerConfigSchema(SampleConfig.class);
        logicalOperators.put(PredicateRegistry.SAMPLE, new ObjectSchema(
                sample.type(), sample.properties(), sample.required(), sample.additionalProperties(),
                sample.minProperties(), sample.maxProperties(), sample.anyOf(), sample.oneOf(),
                sampleDescription.value(), sample.defaultValue(), sample.defaultSnippets()));

        final List<SpiEntry> entries = new ArrayList<>(spiEntries(GatewayPredicateFactory.class, GatewayPredicateFactory::name, GatewayPredicateFactory::configClass));
        entries.addAll(spiEntries(GatewayResponsePredicateFactory.class, GatewayResponsePredicateFactory::name, GatewayResponsePredicateFactory::configClass));

        // r7 takes leg out before a predicate's own parameters bind, so every predicate has it.
        final SchemaNode leg = new PrimitiveSchema("string", Arrays.stream(Leg.values()).map(Leg::configName).toList(), null, null, null,
                "Which leg of the exchange to test: client (the default) or upstream.", null, null, null);

        return buildSpiDefinition(entries, logicalOperators, Map.of(PredicateRegistry.LEG, leg));
    }

    private record SpiEntry(String name, Class<?> configClass, String description)
    {
    }

    private static <T> List<SpiEntry> spiEntries(final Class<T> spiClass, final Function<T, String> nameExtractor, final Function<T, Class<?>> configExtractor)
    {
        final List<SpiEntry> entries = new ArrayList<>();
        ServiceLoader.load(spiClass).forEach(factory -> {
            final Description descAnnotation = factory.getClass().getAnnotation(Description.class);
            entries.add(new SpiEntry(nameExtractor.apply(factory), configExtractor.apply(factory), descAnnotation != null ? descAnnotation.value() : null));
        });
        return entries;
    }

    /**
     * @param commonParameters parameters r7 itself accepts on every entry, beside the entry's own
     */
    private SchemaNode buildSpiDefinition(
            final List<SpiEntry> entries,
            final Map<String, SchemaNode> extraProperties,
            final Map<String, SchemaNode> commonParameters)
    {
        final Map<String, SchemaNode> properties = new TreeMap<>(extraProperties);
        final List<String> parameterlessComponents = new ArrayList<>();

        entries.forEach(entry -> {
            final String name = entry.name();
            final Class<?> configClass = entry.configClass();
            final String description = entry.description();

            if (configClass != null && configClass.isRecord())
            {
                if (configClass.getRecordComponents().length == 0)
                {
                    parameterlessComponents.add(name);
                    if (!commonParameters.isEmpty())
                    {
                        properties.put(name, new ObjectSchema("object", new TreeMap<>(commonParameters), null, new BoolProps(false),
                                null, null, null, null, description, null, null));
                    }
                }
                else
                {
                    final SchemaNode config = buildInnerConfigSchema(configClass);

                    if (config instanceof ObjectSchema os)
                    {
                        List<Snippet> snippets = null;
                        if (os.required() != null && !os.required().isEmpty())
                        {
                            Map<String, Object> snippetBody = new LinkedHashMap<>();
                            int tabStop = 1;
                            for (String req : os.required())
                            {
                                snippetBody.put(req, "$" + (tabStop++));
                            }
                            snippets = List.of(new Snippet("Insert " + name, description != null ? description : "Configure " + name, snippetBody));
                        }

                        final Map<String, SchemaNode> parameters = new TreeMap<>(os.properties());
                        parameters.putAll(commonParameters);
                        properties.put(name, new ObjectSchema(
                                        os.type(), parameters, os.required(), os.additionalProperties(),
                                        os.minProperties(), os.maxProperties(), os.anyOf(), os.oneOf(),
                                        description, os.defaultValue(), snippets
                                )
                        );
                    }
                    else
                    {
                        properties.put(name, config);
                    }
                }
            }
        });

        final SchemaNode objectSchema = new ObjectSchema(
                "object", properties, null, new BoolProps(true), 1, 1, null, null, null, null, null
        );

        final List<SchemaNode> anyOfList = new ArrayList<>();
        anyOfList.add(objectSchema);

        if (!parameterlessComponents.isEmpty())
        {
            Collections.sort(parameterlessComponents);
            anyOfList.add(new PrimitiveSchema("string", parameterlessComponents, null, null, null, null, null, null, null));
        }

        anyOfList.add(new PrimitiveSchema("string", null, null, null, null, "Custom SPI implementation", null, null, null));

        return new AnyOfSchema(anyOfList, null, null);
    }

    private SchemaNode buildInnerConfigSchema(final Class<?> recordClass)
    {
        final Map<String, SchemaNode> properties = new TreeMap<>();
        final List<String> requiredFields = new ArrayList<>();

        for (final RecordComponent component : recordClass.getRecordComponents())
        {
            final String propertyName = toSnakeCase(component.getName());
            final String description = getDescription(component);
            final String defaultStr = getDefaultValue(component);
            final String customPattern = getPattern(component);

            final Object defaultValue = parseDefaultValue(component.getType(), defaultStr);

            // EXTRACT GENERIC TYPE HERE
            final java.lang.reflect.Type genericType = component.getGenericType();

            // Pass genericType into the mapper
            final SchemaNode propertyNode = mapToSchemaNode(component.getType(), genericType, description, defaultValue, customPattern);
            properties.put(propertyName, propertyNode);

            if (isRequired(component))
            {
                requiredFields.add(propertyName);
            }
        }

        return new ObjectSchema(
                "object",
                properties,
                requiredFields.isEmpty() ? null : requiredFields,
                new BoolProps(false),
                null, null, null, null, null, null, null
        );
    }

    // Added genericType to the signature
    private SchemaNode mapToSchemaNode(final Class<?> type, final java.lang.reflect.Type genericType, final String description, final Object defaultValue, final String customPattern)
    {
        final String placeholderRegex = "^\\$\\{.*\\}$";

        if (type == Duration.class)
        {
            final String basePattern = customPattern != null ? customPattern : "^[0-9]+\\s*(ns|us|ms|s|m|h|d|NS|US|MS|S|M|H|D)$";
            final String patternWithPlaceholder = "^(?:" + basePattern.replace("^", "").replace("$", "") + "|\\$\\{.*\\})$";
            final String friendlyHint = "Format: <number><unit> (e.g., 2s, 5m, 100ms) or a placeholder ${...}";

            return new PrimitiveSchema(
                    "string", null, null, patternWithPlaceholder,
                    Map.of("pattern", friendlyHint),
                    description != null ? description + " (" + friendlyHint + ")" : friendlyHint,
                    defaultValue, null, null
            );
        }
        else if (type == com.ethlo.r7.config.model.DataSize.class)
        {
            final String basePattern = customPattern != null ? customPattern : "^[0-9]+\\s*(B|KB|MB|GB|TB|b|kb|mb|gb|tb)$";
            final String patternWithPlaceholder = "^(?:" + basePattern.replace("^", "").replace("$", "") + "|\\$\\{.*\\})$";
            final String friendlyHint = "Format: <number><unit> (e.g., 10KB, 200MB) or a placeholder ${...}";

            return new PrimitiveSchema(
                    "string", null, null, patternWithPlaceholder,
                    Map.of("pattern", friendlyHint),
                    description != null ? description + " (" + friendlyHint + ")" : friendlyHint,
                    defaultValue, null, null
            );
        }
        else if (type == String.class)
        {
            String effectivePattern = customPattern;
            Map<String, String> errors = null;

            if (customPattern != null)
            {
                String stripped = customPattern.startsWith("^") ? customPattern.substring(1) : customPattern;
                stripped = stripped.endsWith("$") ? stripped.substring(0, stripped.length() - 1) : stripped;

                effectivePattern = "^(?:" + stripped + "|\\$\\{.*\\})$";
                errors = Map.of("pattern", "Must match expected format or be a placeholder ${...}");
            }

            return new PrimitiveSchema("string", null, null, effectivePattern, errors, description, defaultValue, null, null);
        }
        else if (type == com.ethlo.r7.config.model.HttpStatus.class)
        {
            return new AnyOfSchema(
                    List.of(
                            new PrimitiveSchema("integer", null, null, null, null, null, null, 100, 599),
                            new PrimitiveSchema("string", null, null, placeholderRegex, null, null, null, null, null)
                    ),
                    description,
                    defaultValue
            );
        }
        else if (type == int.class || type == Integer.class || type == long.class || type == Long.class)
        {
            return new AnyOfSchema(
                    List.of(
                            new PrimitiveSchema("integer", null, null, null, null, null, null, null, null),
                            new PrimitiveSchema("string", null, null, placeholderRegex, null, null, null, null, null)
                    ),
                    description,
                    defaultValue
            );
        }
        else if (type == float.class || type == Float.class || type == double.class || type == Double.class)
        {
            return new AnyOfSchema(
                    List.of(
                            new PrimitiveSchema("number", null, null, null, null, null, null, null, null),
                            new PrimitiveSchema("string", null, null, placeholderRegex, null, null, null, null, null)
                    ),
                    description,
                    defaultValue
            );
        }
        else if (type == boolean.class || type == Boolean.class)
        {
            return new AnyOfSchema(
                    List.of(
                            new PrimitiveSchema("boolean", null, null, null, null, null, null, null, null),
                            new PrimitiveSchema("string", null, null, placeholderRegex, null, null, null, null, null)
                    ),
                    description,
                    defaultValue
            );
        }
        else if (type.isEnum())
        {
            final List<String> enums = java.util.Arrays.stream(type.getEnumConstants())
                    .map(Object::toString)
                    .toList();

            return new AnyOfSchema(
                    List.of(
                            new PrimitiveSchema("string", enums, null, null, null, null, null, null, null),
                            new PrimitiveSchema("string", null, null, placeholderRegex, null, null, null, null, null)
                    ),
                    description,
                    defaultValue
            );
        }
        else if (type.isRecord())
        {
            return buildInnerConfigSchema(type);
        }
        else if (List.class.isAssignableFrom(type) || Set.class.isAssignableFrom(type))
        {
            // NEW LIST HANDLING LOGIC
            SchemaNode itemsSchema = new PrimitiveSchema("string", null, null, null, null, null, null, null, null); // Default fallback

            if (genericType instanceof java.lang.reflect.ParameterizedType pt)
            {
                final java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
                if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> itemClass)
                {
                    // Recursively map the generic type (e.g., TargetConfig.class).
                    // Pass nulls for descriptions/defaults since those belong to the array wrapper, not the items.
                    itemsSchema = mapToSchemaNode(itemClass, null, null, null, null);
                }
            }

            return new ArraySchema("array", itemsSchema, description, defaultValue);
        }
        else if (java.nio.file.Path.class.isAssignableFrom(type) ||
                java.net.URI.class.isAssignableFrom(type) ||
                java.net.URL.class.isAssignableFrom(type))
        {
            return new PrimitiveSchema("string", null, null, null, null, description, defaultValue, null, null);
        }

        return new ObjectSchema("object", null, null, new BoolProps(true), null, null, null, null, description, defaultValue, null);
    }

    private String getDescription(final RecordComponent component)
    {
        final Description desc = component.getAnnotation(Description.class);
        if (desc != null)
        {
            return desc.value();
        }
        return null;
    }

    private String getDefaultValue(final RecordComponent component)
    {
        final DefaultValue def = component.getAnnotation(DefaultValue.class);
        if (def != null)
        {
            return def.value();
        }
        return null;
    }

    private String getPattern(final RecordComponent component)
    {
        final FormatPattern pat = component.getAnnotation(FormatPattern.class);
        if (pat != null)
        {
            return pat.value();
        }
        return null;
    }

    private boolean isRequired(final RecordComponent component)
    {
        if (Optional.class.isAssignableFrom(component.getType()))
        {
            return false;
        }

        for (final java.lang.annotation.Annotation annotation : component.getAnnotations())
        {
            final String simpleName = annotation.annotationType().getSimpleName();
            if (simpleName.equals("Nullable") || simpleName.equals("DefaultValue"))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * The name the YAML mapper binds a component to. Delegates to Jackson's own SNAKE_CASE rather
     * than reimplementing it: a copy drifts ("ipv6PrefixLength" came out "ipv6prefix_length"),
     * and then the schema documents a key the gateway rejects as unknown.
     */
    private String toSnakeCase(final String camelCase)
    {
        return PropertyNamingStrategies.SNAKE_CASE.nameForField(null, null, camelCase);
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public sealed interface SchemaNode permits AnyOfSchema, ArraySchema, ObjectSchema, OneOfSchema, PrimitiveSchema, RefSchema, RootSchema
    {
    }

    public sealed interface AdditionalProperties permits BoolProps, SchemaProps
    {
    }

    public static final class HttpStatusSerializer extends StdSerializer<HttpStatus>
    {
        public HttpStatusSerializer()
        {
            super(HttpStatus.class);
        }

        @Override
        public void serialize(final HttpStatus value, final JsonGenerator gen, final SerializationContext provider) throws JacksonException
        {
            gen.writeNumber(value.code());
        }
    }

    public record BoolProps(@JsonValue boolean value) implements AdditionalProperties
    {
    }

    public record SchemaProps(@JsonValue SchemaNode schema) implements AdditionalProperties
    {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Snippet(String label, String description, Map<String, Object> body)
    {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record RootSchema(
            @JsonProperty("$schema") String schema,
            String type,
            String title,
            @JsonProperty("$defs") Map<String, SchemaNode> defs,
            Map<String, SchemaNode> properties
    ) implements SchemaNode
    {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ObjectSchema(
            String type,
            Map<String, SchemaNode> properties,
            List<String> required,
            AdditionalProperties additionalProperties,
            Integer minProperties,
            Integer maxProperties,
            List<SchemaNode> anyOf,
            List<SchemaNode> oneOf,
            String description,
            @JsonProperty("default") Object defaultValue,
            List<Snippet> defaultSnippets
    ) implements SchemaNode
    {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record PrimitiveSchema(
            String type,
            @JsonProperty("enum") List<?> enums,
            String format,
            String pattern,
            Map<String, String> errorMessage,
            String description,
            @JsonProperty("default") Object defaultValue,
            Integer minimum,
            Integer maximum
    ) implements SchemaNode
    {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ArraySchema(
            String type,
            SchemaNode items,
            String description,
            @JsonProperty("default") Object defaultValue,
            Integer minItems
    ) implements SchemaNode
    {
        public ArraySchema(final String type, final SchemaNode items, final String description, final Object defaultValue)
        {
            this(type, items, description, defaultValue, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record RefSchema(
            @JsonProperty("$ref") String ref,
            String description,
            @JsonProperty("default") Object defaultValue
    ) implements SchemaNode
    {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record OneOfSchema(
            List<SchemaNode> oneOf,
            String description,
            @JsonProperty("default") Object defaultValue
    ) implements SchemaNode
    {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record AnyOfSchema(
            List<SchemaNode> anyOf,
            String description,
            @JsonProperty("default") Object defaultValue
    ) implements SchemaNode
    {
    }
}