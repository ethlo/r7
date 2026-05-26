package com.ethlo.r7.schema;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.function.Function;

import com.ethlo.r7.config.ConfigurationManager;
import com.ethlo.r7.config.model.HttpStatus;
import com.ethlo.r7.doc.DefaultValue;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.doc.FormatPattern;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
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

    public RootSchema generateGatewaySchema()
    {
        final Map<String, SchemaNode> defs = new TreeMap<>();
        defs.put("filter", buildFilterDefinition());
        defs.put("predicate", buildPredicateDefinition());
        defs.put("route", buildRouteDefinition());

        final Map<String, SchemaNode> properties = new TreeMap<>();

        properties.put("global_filters", new ArraySchema(
                        "array",
                        new RefSchema("#/$defs/filter", null, null),
                        "A list of filters applied globally to every request passing through the gateway.",
                        null
                )
        );

        properties.put("routes", new ArraySchema(
                        "array",
                        new RefSchema("#/$defs/route", null, null),
                        "The routing table defining how incoming traffic is matched and forwarded.",
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
                        "Conditions that must be met for this route to handle a request. If omitted, all requests match.",
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

        properties.put("upstream", buildUpstreamSchema());
        properties.put("journal", buildJournalSchema());

        final SchemaNode requireUpstream = new ObjectSchema(null, null, List.of("upstream"), null, null, null, null, null, null, null, null);
        final SchemaNode requireFilters = new ObjectSchema(null, null, List.of("filters"), null, null, null, null, null, null, null, null);

        return new ObjectSchema(
                "object",
                properties,
                List.of("id"),
                new BoolProps(false),
                null, null,
                List.of(requireUpstream, requireFilters),
                null,
                "Defines a single traffic route and its associated behaviors.",
                null,
                null
        );
    }

    private SchemaNode buildUpstreamSchema()
    {
        final SchemaNode urlSchema = new PrimitiveSchema(
                "string", null, null,
                "^https?://.*$",
                Map.of("pattern", "URL must start with http:// or https://"),
                "The target URL (Must start with http:// or https://).",
                null, null, null
        );

        final SchemaNode targetItem = new ObjectSchema(
                "object",
                Map.of("url", urlSchema),
                List.of("url"),
                new BoolProps(false),
                null, null, null, null,
                "An individual backend server.",
                null,
                null
        );

        final SchemaNode pathSchema = new PrimitiveSchema(
                "string", null, null,
                "^/.*$",
                Map.of("pattern", "Path must start with a forward slash (/)"),
                "The URI path used to check the health of the target (e.g., /health).",
                null, null, null
        );

        final SchemaNode health = new ObjectSchema(
                "object",
                Map.of("path", pathSchema),
                List.of("path"),
                new BoolProps(false),
                null, null, null, null,
                "Active health checking configuration for the backend targets.",
                null,
                null
        );

        final SchemaNode routeIdSchema = new PrimitiveSchema(
                "string", null, null, null, null,
                "The ID of another route to execute if this upstream becomes completely unavailable.",
                null, null, null
        );

        final SchemaNode fallback = new ObjectSchema(
                "object",
                Map.of("route_id", routeIdSchema),
                List.of("route_id"),
                new BoolProps(false),
                null, null, null, null,
                "Fallback routing behavior triggered when all upstream targets fail.",
                null,
                null
        );

        final Map<String, SchemaNode> props = new TreeMap<>(Map.of(
                "targets", new ArraySchema("array", targetItem, "List of backend servers to forward traffic to.", null),
                "health_check", health,
                "fallback", fallback
        ));

        return new ObjectSchema(
                "object",
                props,
                List.of("targets"),
                new BoolProps(false),
                null, null, null, null,
                "Configuration for routing traffic to backend services.",
                null,
                null
        );
    }

    private SchemaNode buildJournalSchema()
    {
        final SchemaNode levelSchema = new PrimitiveSchema(
                "string",
                List.of("NONE", "HEADERS", "METADATA", "FULL"),
                null, null, null,
                "The base journaling verbosity level.",
                null, null, null
        );

        final SchemaNode overridesSchema = new ObjectSchema(
                "object", null, null,
                new SchemaProps(new PrimitiveSchema("string", null, null, null, null, null, null, null, null)),
                null, null, null, null,
                "Overrides for the journal level based on specific HTTP status codes or classes (e.g., '5xx': 'FULL', '404': 'NONE').",
                null,
                null
        );

        final Map<String, SchemaNode> levelDefProps = new TreeMap<>(Map.of(
                "level", levelSchema,
                "status_overrides", overridesSchema
        ));

        final SchemaNode levelDef = new ObjectSchema(
                "object",
                levelDefProps,
                null,
                new BoolProps(false),
                null, null, null, null,
                "Journal settings for this specific phase of the exchange.",
                null,
                null
        );

        return new ObjectSchema(
                "object",
                new TreeMap<>(Map.of("request", levelDef, "response", levelDef)),
                null,
                new BoolProps(false),
                null, null, null, null,
                "Observability and logging configuration for this specific route.",
                null,
                null
        );
    }

    private SchemaNode buildFilterDefinition()
    {
        return buildSpiDefinition(
                GatewayFilterFactory.class,
                GatewayFilterFactory::name,
                GatewayFilterFactory::configClass,
                Map.of()
        );
    }

    private SchemaNode buildPredicateDefinition()
    {
        final Map<String, SchemaNode> logicalOperators = new TreeMap<>();

        logicalOperators.put("not", new RefSchema("#/$defs/predicate", null, null));

        final SchemaNode arrayRef = new ArraySchema("array", new RefSchema("#/$defs/predicate", null, null), null, null);
        logicalOperators.put("or", arrayRef);
        logicalOperators.put("and", arrayRef);

        return buildSpiDefinition(
                GatewayPredicateFactory.class,
                GatewayPredicateFactory::name,
                GatewayPredicateFactory::configClass,
                logicalOperators
        );
    }

    private <T> SchemaNode buildSpiDefinition(
            final Class<T> spiClass,
            final Function<T, String> nameExtractor,
            final Function<T, Class<?>> configExtractor,
            final Map<String, SchemaNode> extraProperties)
    {
        final Map<String, SchemaNode> properties = new TreeMap<>(extraProperties);
        final List<String> parameterlessComponents = new ArrayList<>();

        ServiceLoader.load(spiClass).forEach(factory -> {
            final String name = nameExtractor.apply(factory);
            final Class<?> configClass = configExtractor.apply(factory);

            final Description descAnnotation = factory.getClass().getAnnotation(Description.class);
            final String description = (descAnnotation != null) ? descAnnotation.value() : null;

            if (configClass != null && configClass.isRecord())
            {
                if (configClass.getRecordComponents().length == 0)
                {
                    parameterlessComponents.add(name);
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

                        properties.put(name, new ObjectSchema(
                                        os.type(), os.properties(), os.required(), os.additionalProperties(),
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
            final String defaultValue = getDefaultValue(component);
            final String customPattern = getPattern(component);

            final SchemaNode propertyNode = mapToSchemaNode(component.getType(), description, defaultValue, customPattern);
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

    private SchemaNode mapToSchemaNode(final Class<?> type, final String description, final String defaultValue, final String customPattern)
    {
        final String placeholderRegex = "^\\$\\{.*\\}$";

        if (type == java.time.Duration.class)
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
        else if (type.getSimpleName().equals("DataSize"))
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
        else if (type.getSimpleName().equals("HttpStatus"))
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
            final List<String> enums = Arrays.stream(type.getEnumConstants())
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
        else if (List.class.isAssignableFrom(type))
        {
            return new ArraySchema("array", new PrimitiveSchema("string", null, null, null, null, null, null, null, null), description, defaultValue);
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

    private String toSnakeCase(final String camelCase)
    {
        if (camelCase == null || camelCase.isEmpty())
        {
            return camelCase;
        }

        final StringBuilder result = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++)
        {
            final char c = camelCase.charAt(i);

            if (Character.isUpperCase(c))
            {
                if (i > 0)
                {
                    final char prev = camelCase.charAt(i - 1);
                    final boolean isNextLower = (i < camelCase.length() - 1) && Character.isLowerCase(camelCase.charAt(i + 1));

                    if (Character.isLowerCase(prev) || (Character.isUpperCase(prev) && isNextLower))
                    {
                        result.append('_');
                    }
                }
                result.append(Character.toLowerCase(c));
            }
            else
            {
                result.append(c);
            }
        }
        return result.toString();
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public sealed interface SchemaNode permits AnyOfSchema, ArraySchema, ObjectSchema, OneOfSchema, PrimitiveSchema, RefSchema, RootSchema
    {
    }

    public sealed interface AdditionalProperties permits BoolProps, SchemaProps
    {
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
            @JsonProperty("default") String defaultValue,
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
            @JsonProperty("default") String defaultValue,
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
            @JsonProperty("default") String defaultValue
    ) implements SchemaNode
    {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record RefSchema(
            @JsonProperty("$ref") String ref,
            String description,
            @JsonProperty("default") String defaultValue
    ) implements SchemaNode
    {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record OneOfSchema(
            List<SchemaNode> oneOf,
            String description,
            @JsonProperty("default") String defaultValue
    ) implements SchemaNode
    {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record AnyOfSchema(
            List<SchemaNode> anyOf,
            String description,
            @JsonProperty("default") String defaultValue
    ) implements SchemaNode
    {
    }
}